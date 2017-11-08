package cz.it4i.fiji.scpclient;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Identity;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

public class ScpClient implements Closeable {

	public static void main(String[] args) throws JSchException, IOException {
		try (ScpClient scpClient = new ScpClient("salomon.it4i.cz", "koz01", "/home/koz01/.ssh/it4i_rsa",
				"nejlepsivyzkum")) {
			boolean u = scpClient.upload(Paths.get("/home/koz01/aaa/vecmath.jar"), "/home/koz01/");
			boolean d = scpClient.download("/home/koz01/proof", Paths.get("/home/koz01/aaa/proof"));
			System.out.println(u);
			System.out.println(d);
		}
	}

	private String hostName;
	private String username;
	private JSch jsch = new JSch();
	private Session session;

	public ScpClient(String hostName, String username, Identity privateKeyFile) throws JSchException {
		super();
		init(hostName, username, privateKeyFile);
	}

	public ScpClient(String hostName, String userName, String keyFile, String pass) throws JSchException {
		Identity id = IdentityFile.newInstance(keyFile, null, jsch);
		try {
			id.setPassphrase(pass.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
		init(hostName, userName, id);
	}

	private void init(String hostName, String username, Identity privateKeyFile) throws JSchException {
		this.hostName = hostName;
		this.username = username;
		jsch.addIdentity(privateKeyFile, null);
	}

	public boolean download(String lfile, Path rfile) throws JSchException, IOException {
		Session session = connectionSession();

		// exec 'scp -f rfile' remotely
		String command = "scp -f " + lfile;
		Channel channel = session.openChannel("exec");

		try {
			((ChannelExec) channel).setCommand(command);

			// get I/O streams for remote scp
			try (OutputStream out = channel.getOutputStream(); InputStream in = channel.getInputStream()) {

				channel.connect();

				byte[] buf = new byte[1024];

				// send '\0'
				buf[0] = 0;
				out.write(buf, 0, 1);
				out.flush();

				while (true) {
					int c = checkAck(in);
					if (c != 'C') {
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
						if (buf[0] == ' ')
							break;
						filesize = filesize * 10L + (long) (buf[0] - '0');
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
					try (OutputStream fos = Files.newOutputStream(rfile)) {
						int foo;
						while (true) {
							if (buf.length < filesize)
								foo = buf.length;
							else
								foo = (int) filesize;
							foo = in.read(buf, 0, foo);
							if (foo < 0) {
								// error
								break;
							}
							fos.write(buf, 0, foo);
							filesize -= foo;
							if (filesize == 0L)
								break;
						}
					}
					if (checkAck(in) != 0) {
						return false;
					}

					// send '\0'
					buf[0] = 0;
					out.write(buf, 0, 1);
					out.flush();

				}
			}

		} finally {
			channel.disconnect();
		}
		return true;
	}

	public boolean upload(Path file, String rfile) throws JSchException, IOException {

		Session session = connectionSession();

		boolean ptimestamp = true;

		// exec 'scp -t rfile' remotely
		String command = "scp " + (ptimestamp ? "-p" : "") + " -t " + rfile;
		Channel channel = session.openChannel("exec");
		((ChannelExec) channel).setCommand(command);
		// get I/O streams for remote scp
		try (OutputStream out = channel.getOutputStream(); InputStream in = channel.getInputStream()) {
			channel.connect();
			if (checkAck(in) != 0) {
				return false;
			}

			if (ptimestamp) {
				command = "T " + (file.toFile().lastModified() / 1000) + " 0";
				// The access time should be sent here,
				// but it is not accessible with JavaAPI ;-<
				command += (" " + (file.toFile().lastModified() / 1000) + " 0\n");
				out.write(command.getBytes());
				out.flush();
				if (checkAck(in) != 0) {
					return false;
				}
			}

			// send "C0644 filesize filename", where filename should not include '/'
			long filesize = file.toFile().length();
			command = "C0644 " + filesize + " ";
			command += file.getFileName().toString();
			command += "\n";
			out.write(command.getBytes());
			out.flush();
			if (checkAck(in) != 0) {
				return false;
			}
			byte[] buf = new byte[1024];
			// send a content of lfile
			try (InputStream fis = Files.newInputStream(file)) {
				while (true) {
					int len = fis.read(buf, 0, buf.length);
					if (len <= 0)
						break;
					out.write(buf, 0, len); // out.flush();
				}
			}
			// send '\0'
			buf[0] = 0;
			out.write(buf, 0, 1);
			out.flush();
			if (checkAck(in) != 0) {
				return false;
			}
			out.close();

		} finally {
			channel.disconnect();
		}
		return true;
	}

	private Session connectionSession() throws JSchException {
		if (session == null) {
			session = jsch.getSession(username, hostName);

			UserInfo ui = new P_UserInfo();

			session.setUserInfo(ui);
		}
		if (!session.isConnected()) {
			session.connect();
		}
		return session;
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
		public void showMessage(String message) {
		}

	}

	static int checkAck(InputStream in) throws IOException {
		int b = in.read();
		// b may be 0 for success,
		// 1 for error,
		// 2 for fatal error,
		// -1
		if (b == 0)
			return b;
		if (b == -1)
			return b;

		if (b == 1 || b == 2) {
			StringBuffer sb = new StringBuffer();
			int c;
			do {
				c = in.read();
				sb.append((char) c);
			} while (c != '\n');
			if (b == 1) { // error
				System.out.print(sb.toString());
			}
			if (b == 2) { // fatal error
				System.out.print(sb.toString());
			}
		}
		return b;
	}

	@Override
	public void close() {
		if (session.isConnected()) {
			session.disconnect();
		}
	}
}
