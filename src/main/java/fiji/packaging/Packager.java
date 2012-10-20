package fiji.packaging;

import ij.IJ;
import imagej.updater.core.Checksummer;
import imagej.updater.core.FileObject;
import imagej.updater.core.FilesCollection;
import imagej.updater.util.Progress;
import imagej.updater.util.StderrProgress;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public abstract class Packager {
	protected File ijDir;
	protected Collection<String> files;
	protected int count, total;

	protected byte[] buffer = new byte[16384];

	public abstract String getExtension();

	public abstract void open(OutputStream out) throws IOException;
	public abstract void putNextEntry(String name, boolean executable, int size) throws IOException;
	public abstract void write(byte[] b, int off, int len) throws IOException;
	public abstract void closeEntry() throws IOException;
	public abstract void close() throws IOException;

	public void write(InputStream in) throws IOException {
		for (;;) {
			int count = in.read(buffer);
			if (count < 0)
				break;
			write(buffer, 0, count);
		}
		in.close();
	}

	public void initialize(String... platforms) throws Exception {
		if (System.getProperty("ij.dir") == null)
			throw new UnsupportedOperationException("Need an ij.dir property pointing to the ImageJ root!");
		ijDir = new File(System.getProperty("ij.dir"));
		files = getFileList(platforms);
	}

	private List<String> getFileList(String... platforms) {
		final Progress progress = IJ.getInstance() == null ? new StderrProgress() : new Progress() {
			@Override
			public void setTitle(String title) {
				IJ.showStatus(title);
			}

			@Override
			public void setCount(int count, int total) {
				IJ.showProgress(count, total);
			}

			@Override
			public void addItem(Object item) {
				IJ.showStatus("" + item);
			}

			@Override
			public void setItemCount(int count, int total) {
			}

			@Override
			public void itemDone(Object item) {
			}

			@Override
			public void done() {
				IJ.showStatus("Finished checksumming");
			}

		};
		final FilesCollection files = new FilesCollection(ijDir);
		final Checksummer checksummer = new Checksummer(files, progress);

		checksummer.updateFromLocal();
		files.sort();
		List<String> result = new ArrayList<String>();
		for (final FileObject file : files) {
			if (isForPlatforms(file, platforms))
				result.add(file.getLocalFilename(false));
		}

		return result;
	}

	private static boolean isForPlatforms(final FileObject file, String... platforms) {
		if (platforms.length == 0)
			return true;
		for (final String platform : platforms) {
			if (file.isForPlatform(platform))
				return true;
		}
		return false;
	}

	protected void addDefaultFiles() throws IOException {
		addFile("db.xml.gz", false);
		// Maybe ImageJ or ImageJ.exe exist?
		addFile("ImageJ", true);
		addFile("ImageJ.exe", true);
		addFile("Contents/Info.plist", false);
		for (String fileName : files)
			addFile(fileName, false);
	}

	public void addJRE(String... platforms) throws IOException {
		final File javaDir = new File(ijDir, "java");
		final List<String> directories = new ArrayList<String>();
		if (platforms.length == 0) {
			final String[] candidates = javaDir.list(new NoHiddenFiles());
			if (candidates == null)
				throw new IOException("No JREs found in " + javaDir);
			for (final String candidate : candidates) {
				String dir = getNewestJRE("java/" + candidate);
				if (dir != null)
					directories.add(dir);
			}
		}
		else
			for (int i = 0; i < platforms.length; i++) {
				final String platform = platforms[i];
				String dir = getNewestJRE("java/" + platform);
				if (dir == null) {
					if ("linux32".equals(platform))
						dir = "linux";
					else if ("linux64".equals(platform))
						dir = "linux-amd64";
					else if ("macosx".equals(platform) || "tiger".equals(platform))
						dir = "macosx-java3d";

					dir = getNewestJRE("java/" + dir);
					if (dir == null) {
						System.err.println("No JRE found for platform '" + platform);
						System.exit(1);
					}
				}
				directories.add(dir);
			}

		for (final String dir : directories)
			addDirectory(dir);
	}

	private static class NoHiddenFiles implements FilenameFilter {
		@Override
		public boolean accept(final File file, final String name) {
			return !name.startsWith(".");
		}
	}

	private String getNewestJRE(String dirName) {
		final File[] list = new File(ijDir, dirName).listFiles();
		if (list == null)
			return null;
		File result = null;
		for (final File file : list) {
			if (!file.isDirectory())
				continue;
			final File jre = new File(file, "jre");
			if (!jre.isDirectory())
				continue;
			if (result == null || result.lastModified() < jre.lastModified())
				result = file;
		}
		if (result == null)
			return null;
		return dirName + "/" + result.getName() + "/jre";
	}

	private boolean addDirectory(String dirName) throws IOException {
		final File[] list = new File(ijDir, dirName).listFiles(new NoHiddenFiles());
		if (list == null)
			return false;
		boolean result = true;
		for (final File file : list)
			if (file.isDirectory())
				result = addDirectory(dirName + "/" + file.getName()) && result;
			else if (file.isFile())
				result = addFile(dirName + "/" + file.getName(), false) && result;
		return result;
	}

	protected boolean addFile(String fileName, boolean executable) throws IOException {
		count++;
		if (fileName.equals("ImageJ-macosx") || fileName.equals("ImageJ-tiger"))
			fileName = "Contents/MacOS/" + fileName;
		File file = new File(ijDir, fileName);
		if (!file.exists())
			return false;
		putNextEntry("Fiji.app/" + fileName, executable || file.canExecute(), (int)file.length());
		write(new FileInputStream(file));
		closeEntry();
		return true;
	}

	protected static String getPlatform() {
		final boolean is64bit = System.getProperty("os.arch", "").indexOf("64") >= 0;
		final String osName = System.getProperty("os.name", "<unknown>");
		if (osName.equals("Linux"))
			return "linux" + (is64bit ? "64" : "32");
		if (osName.equals("Mac OS X"))
			return "macosx";
		if (osName.startsWith("Windows"))
			return "win" + (is64bit ? "64" : "32");
		// System.err.println("Unknown platform: " + osName);
		return osName.toLowerCase();
	}

	public static void main(String[] args) {
		boolean includeJRE = false;
		String[] platforms = {};
		int i = 0;
		while (i < args.length && args[i].startsWith("-")) {
			if (args[i].equals("--jre"))
				includeJRE = true;
			else if (args[i].startsWith("--platforms=")) {
				platforms = args[i].substring("--platforms=".length()).split(",");
				if (platforms.length == 1 && "".equals(platforms[0]))
					platforms = new String[0];
			}
			else if (args[i].startsWith("-Dij.dir="))
				System.setProperty("ij.dir", args[i].substring("-Dij.dir=".length()));
			else {
				System.err.println("Unknown option: " + args[i]);
				System.exit(1);
			}
			i++;
		}
		Packager packager = null;
		if (i + 1 == args.length) {
			if (args[i].endsWith(".zip"))
				packager = new ZipPackager();
			else if (args[i].endsWith(".tar"))
				packager = new TarPackager();
			else if (args[i].endsWith(".tar.gz") || args[0].endsWith(".tgz"))
				packager = new TarGzPackager();
			else if (args[i].endsWith(".tar.bz2") || args[0].endsWith(".tbz")) try {
				packager = new TarBz2Packager();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
				System.exit(1);
			}
			else {
				System.err.println("Unsupported archive format: " + args[i]);
				System.exit(1);
			}
		}
		else {
			System.err.println("Usage: Package_Maker [--platform=<platform>[,<platform>]] [--jre] <filename>");
			System.exit(1);
		}
		String path = args[i];

		try {
			packager.initialize(platforms);
			packager.open(new FileOutputStream(path));
			packager.addDefaultFiles();
			if (includeJRE)
				packager.addJRE(platforms);
			packager.close();
		}
		catch (Exception e) {
			e.printStackTrace();
			System.err.println("Error writing " + path);
		}
	}
}