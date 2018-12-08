
package cz.it4i.fiji.scpclient;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.Identity;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cz.it4i.fiji.commons.DoActionEventualy;

public class ScpClient implements Closeable {

	private static final String NO_SUCH_FILE_OR_DIRECTORY_ERROR_TEXT =
		"No such file or directory";

	public static final Logger log = LoggerFactory.getLogger(
		cz.it4i.fiji.scpclient.ScpClient.class);
	
	private static String constructExceptionText(AckowledgementChecker ack) {
		return "Check acknowledgement failed with status: " + ack.getLastStatus() +
			" and message: " + ack.getLastMessage();
	}
	
	private static final int MAX_NUMBER_OF_CONNECTION_ATTEMPTS = 5;

	private static final long TIMEOUT_BETWEEN_CONNECTION_ATTEMPTS = 500;

	private String hostName;
	private String username;
	private final JSch jsch = new JSch();
	private Session session;
	private final TransferFileProgress dummyProgress =
		new TransferFileProgress()
		{

			@Override
			public void dataTransfered(long bytesTransfered) {

		}
		};

	public ScpClient(String hostName, String username, byte[] privateKeyFile)
		throws JSchException
	{
		init(hostName, username, new ByteIdentity(jsch, privateKeyFile));
	}

	public ScpClient(String hostName, String username, Identity privateKeyFile)
		throws JSchException
	{
		init(hostName, username, privateKeyFile);
	}

	public ScpClient(String hostName, String userName, String keyFile,
		String pass) throws JSchException
	{
		Identity id = IdentityFile.newInstance(keyFile, null, jsch);
		try {
			if (pass != null) {
				id.setPassphrase(pass.getBytes("UTF-8"));
			}
		}
		catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
		init(hostName, userName, id);
	}

	private void init(String initHostName, String initUsername,
		Identity privateKeyFile) throws JSchException
	{
		this.hostName = initHostName;
		this.username = initUsername;
		jsch.addIdentity(privateKeyFile, null);
	}

	public void download(String lfile, Path rFile) throws JSchException,
		IOException
	{
		download(lfile, rFile, dummyProgress);
	}

	public void download(String lfile, Path rfile,
		TransferFileProgress progress) throws JSchException, IOException
	{
		if (!Files.exists(rfile.getParent())) {
			Files.createDirectories(rfile.getParent());
		}
		try (OutputStream os = Files.newOutputStream(rfile)) {
			download(lfile, os, progress);
		}
	}

	public void download(String lfile, OutputStream os,
		TransferFileProgress progress) throws JSchException, IOException
	{
		AckowledgementChecker ack = new AckowledgementChecker();
		// exec 'scp -f rfile' remotely
		String command = "scp -f " + lfile;
		Channel channel = getConnectedSession().openChannel("exec");

		try {
			((ChannelExec) channel).setCommand(command);

			// get I/O streams for remote scp
			try (OutputStream out = channel.getOutputStream();
					InputStream in = channel.getInputStream())
			{

				channel.connect();

				byte[] buf = new byte[getBufferSize()];

				// send '\0'
				buf[0] = 0;
				out.write(buf, 0, 1);
				out.flush();

				while (true) {
					ack.checkAck(in);
					if (ack.getLastStatus() != 'C') {
						break;
					}

					// read '0644 '
					in.read(buf, 0, 5);

					long filesize = 0L;
					while (true) {
						if (in.read(buf, 0, 1) < 0) {
							// error
							break;
						}
						if (buf[0] == ' ') break;
						filesize = filesize * 10L + buf[0] - '0';
					}

					@SuppressWarnings("unused")
					String file = null;
					for (int i = 0;; i++) {
						in.read(buf, i, 1);
						if (buf[i] == (byte) 0x0a) {
							file = new String(buf, 0, i);
							break;
						}
					}

					// System.out.println("filesize="+filesize+", file="+file);

					// send '\0'
					
					buf[0] = 0;
					out.write(buf, 0, 1);
					out.flush();

					// read a content of lfile
					int foo;
					while (true) {
						if (buf.length < filesize) foo = buf.length;
						else foo = (int) filesize;
						foo = in.read(buf, 0, foo);
						if (foo < 0) {
							// error
							break;
						}
						os.write(buf, 0, foo);
						progress.dataTransfered(foo);
						filesize -= foo;
						if (filesize == 0L) break;
					}

					if (!ack.checkAck(in)) {
						throw new JSchException(constructExceptionText(ack));
					}

					// send '\0'
					buf[0] = 0;
					out.write(buf, 0, 1);
					out.flush();

				}
			}

		}
		catch (ClosedByInterruptException e) {
			throw new InterruptedIOException();
		}
		finally {
			channel.disconnect();
		}
	}

	public void upload(Path file, String rfile) throws JSchException,
		IOException
	{
		upload(file, rfile, dummyProgress);
	}

	public void upload(Path file, String rfile, TransferFileProgress progress)
		throws JSchException, IOException
	{
		try (InputStream is = Files.newInputStream(file)) {
			upload(is, rfile, file.toFile().length(), file.toFile()
				.lastModified(), progress);
		}
	}

	public void upload(InputStream is, String fileName, long length,
		long lastModified, TransferFileProgress progress) throws JSchException,
		IOException
	{
		int noSuchFileExceptionThrown = 0;
		do {
			try {
				scp2Server(is, fileName, length, lastModified, progress);
				break;
			} catch (NoSuchFileException e) {
				if (noSuchFileExceptionThrown > MAX_NUMBER_OF_CONNECTION_ATTEMPTS) {
					throw new JSchException(e.getReason());
				}
				if (noSuchFileExceptionThrown > 0) {
					try {
						Thread.sleep(TIMEOUT_BETWEEN_CONNECTION_ATTEMPTS);
					}
					catch (InterruptedException exc) {
					}
				}
				mkdir(e.getFile());
				noSuchFileExceptionThrown++;
				continue;
			}
		} while(true);
	}

	public long size(String lfile) throws JSchException, IOException {
		AckowledgementChecker ack = new AckowledgementChecker();
		// exec 'scp -f rfile' remotely
		String command = "scp -f " + lfile;
		Channel channel = getConnectedSession().openChannel("exec");

		try {
			((ChannelExec) channel).setCommand(command);

			// get I/O streams for remote scp
			try (OutputStream out = channel.getOutputStream();
					InputStream in = channel.getInputStream())
			{

				channel.connect();

				byte[] buf = new byte[getBufferSize()];

				// send '\0'
				buf[0] = 0;
				out.write(buf, 0, 1);
				out.flush();

				while (true) {
					ack.checkAck(in);
					if (ack.getLastStatus() != 'C') {
						break;
					}

					// read '0644 '
					in.read(buf, 0, 5);

					long filesize = 0L;
					while (true) {
						if (in.read(buf, 0, 1) < 0) {
							// error
							break;
						}
						if (buf[0] == ' ') break;
						filesize = filesize * 10L + buf[0] - '0';
					}
					return filesize;

				}
			}

		}
		finally {
			channel.disconnect();
		}
		return -1;
	}

	@SuppressWarnings("unchecked")
	public List<Long> sizeByLs(String lfile) throws JSchException {

		// exec 'scp -f rfile' remotely
		Channel channel = getConnectedSession().openChannel("sftp");

		try {
			channel.connect();
			return ((List<LsEntry>) ((ChannelSftp) channel).ls(lfile)).stream().map(
				atr -> atr.getAttrs().getSize()).collect(Collectors.toList());

		}
		catch (SftpException e) {
			e.printStackTrace();
		}
		finally {
			channel.disconnect();
		}
		return null;
	}

	@Override
	public void close() {
		if (session != null && session.isConnected()) {
			// log.info("disconnect");
			try(DoActionEventualy actionEventualy = new DoActionEventualy(TIMEOUT_BETWEEN_CONNECTION_ATTEMPTS, this::interruptSessionThread)){
				session.disconnect();
			}
		}
		session = null;
	}

	private void interruptSessionThread() {
		try {
			Field f=  session.getClass().getDeclaredField("connectThread");
			if(!f.isAccessible()) {
				f.setAccessible(true);
				Thread thread = (Thread) f.get(session);
				thread.interrupt();
			}
		}
		catch (NoSuchFieldException | SecurityException | IllegalArgumentException
				| IllegalAccessException exc)
		{
			log.error(exc.getMessage(), exc);
		}
	}

	private int getBufferSize() {
		return 1024 * 1024;
	}

	private Session getConnectedSession() throws JSchException {
		if (session == null) {
			session = jsch.getSession(username, hostName);

			UserInfo ui = new P_UserInfo();

			session.setUserInfo(ui);
		}
		int connectRetry = 0;
		long timoutBetweenRetry = TIMEOUT_BETWEEN_CONNECTION_ATTEMPTS;
		while (!session.isConnected()) {
			try {
				session.connect();
			}
			catch(JSchException e) {
				if(e.getMessage().contains("Auth fail") || e.getMessage().contains("Packet corrupt")) {
					if(connectRetry < MAX_NUMBER_OF_CONNECTION_ATTEMPTS) {
						connectRetry++;
						try {
							Thread.sleep(timoutBetweenRetry);
							timoutBetweenRetry *= 2;
						}
						catch (InterruptedException exc) {
							log.info("Interruption detected");
							throw new JSchException(exc.getMessage(), exc);
						}
						continue;
					} 
					e = new AuthFailException(e.getMessage(), e); 
				}
				throw e;
			}
		}
		return session;
	}

	private void scp2Server(InputStream is, String fileName, long length,
		long lastModified, TransferFileProgress progress) throws JSchException,
		IOException, InterruptedIOException
	{
		AckowledgementChecker ack = new AckowledgementChecker();
		boolean ptimestamp = false;
		// exec 'scp -t rfile' remotely
		String command = "scp " + (ptimestamp ? "-p" : "") + " -t '" + fileName + "'";
		Channel channel = getConnectedSession().openChannel("exec");
		((ChannelExec) channel).setCommand(command);
		// get I/O streams for remote scp
		try (OutputStream out = channel.getOutputStream();
				InputStream in = channel.getInputStream())
		{
			channel.connect();
			if (!ack.checkAck(in)) {
				throw new JSchException(constructExceptionText(ack));
			}
	
			if (ptimestamp) {
				command = "T " + (lastModified / 1000) + " 0";
				// The access time should be sent here,
				// but it is not accessible with JavaAPI ;-<
				command += (" " + (lastModified / 1000) + " 0\n");
				out.write(command.getBytes());
				out.flush();
				if (!ack.checkAck(in)) {
					throw new JSchException(constructExceptionText(ack));
				}
			}
	
			// send "C0644 filesize filename", where filename should not include '/'
			long filesize = length;
			command = "C0644 " + filesize + " ";
			command += Paths.get(fileName).getFileName().toString();
			command += "\n";
			out.write(command.getBytes());
			out.flush();
			if (!ack.checkAck(in)) {
				if (ack.getLastStatus() == 1 && ack.getLastMessage().contains(
					NO_SUCH_FILE_OR_DIRECTORY_ERROR_TEXT))
				{
					throw new NoSuchFileException(getParent(fileName), null,
						constructExceptionText(ack));
				}
				throw new JSchException(constructExceptionText(ack));
			}
			byte[] buf = new byte[getBufferSize()];
			// send a content of lfile
			while (true) {
				int len = is.read(buf, 0, buf.length);
				if (len <= 0) break;
				out.write(buf, 0, len); // out.flush();
				progress.dataTransfered(len);
			}
			// send '\0'
			buf[0] = 0;
			out.write(buf, 0, 1);
			out.flush();
			if (!ack.checkAck(in)) {
				throw new JSchException(constructExceptionText(ack));
			}
			out.close();
	
		}
		catch (ClosedByInterruptException e) {
			Thread.interrupted();
			throw new InterruptedIOException();
		}
		finally {
			channel.disconnect();
		}
	}

	private int mkdir(String file) throws JSchException {
		ChannelExec channel = (ChannelExec) getConnectedSession().openChannel("exec");
		channel.setCommand("mkdir -p '" + file + "'");
		try {
			channel.connect();
			return channel.getExitStatus();
		} finally {
			channel.disconnect();
		}
	}

	private String getParent(String fileName) {
		int index = fileName.lastIndexOf('/');
		if (index == -1) {
			return null;
		}
		return fileName.substring(0, index);
	}

	private class P_UserInfo implements UserInfo {

		@Override
		public String getPassphrase() {
			return null;
		}

		@Override
		public String getPassword() {
			return null;
		}

		@Override
		public boolean promptPassword(String message) {
			return false;
		}

		@Override
		public boolean promptPassphrase(String message) {
			return false;
		}

		@Override
		public boolean promptYesNo(String message) {
			return true;
		}

		@Override
		public void showMessage(String message) {}

	}

	
	
}
