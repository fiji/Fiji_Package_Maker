package fiji.packaging;

import ij.IJ;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import net.imagej.updater.Checksummer;
import net.imagej.updater.FileObject;
import net.imagej.updater.FilesCollection;
import net.imagej.updater.util.Progress;

public abstract class Packager {
	protected File ijDir;
	protected Collection<String> files;
	protected String prefix = "Fiji.app/";

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

	public void setRootDirectory(final File rootDirectory) {
		ijDir = rootDirectory;
	}

	public void setPrefix(final String prefix) {
		this.prefix = prefix;
	}

	public void initialize(boolean includeJRE, String... platforms) throws Exception {
		if (System.getProperty("ij.dir") == null)
			throw new UnsupportedOperationException("Need an ij.dir property pointing to the ImageJ root!");
		ijDir = new File(System.getProperty("ij.dir"));
		files = new LinkedHashSet<String>();
		files.add("db.xml.gz");
		// Maybe ImageJ or ImageJ.exe exist?
		for (final String fileName : new String[] { "ImageJ", "ImageJ.exe" })
			if (new File(ijDir, fileName).exists())
				files.add("ImageJ");
		files.add("Contents/Info.plist");
		adapter.getFileList(files, ijDir, platforms);
		if (includeJRE)
			getJREFiles(platforms);
	}

	/**
	 * An interface to hide the implementation details of the updater.
	 */
	private interface Adapter {
		void getFileList(Collection<String> list, File ijDir, String... platforms);
	}

	private static Adapter adapter;
	static {
		try {
			adapter = new ModernUpdater();
		} catch (Throwable t) {
			t.printStackTrace();
			adapter = new LegacyUpdater();
		}
	}

	/**
	 * An adapter for the current updater (encapsulated to handle version skew by falling back to {@link LegacyUpdater}).
	 */
	private static class ModernUpdater implements Adapter {
		private final Progress progress = IJ.getInstance() == null ? null : new ProgressAdapter();

		@Override
		public void getFileList(Collection<String> list, File ijDir, String... platforms) {
			final FilesCollection files = new FilesCollection(ijDir);
			final Checksummer checksummer = new Checksummer(files, progress);

			checksummer.updateFromLocal();
			files.sort();
			for (final FileObject file : files) {
				if (isForPlatforms(file, platforms))
					list.add(file.getLocalFilename(false));
			}
		}

		private boolean isForPlatforms(final FileObject file, String... platforms) {
			if (platforms.length == 0 || !file.getPlatforms().iterator().hasNext())
				return true;
			for (final String platform : platforms) {
				if (file.isForPlatform(platform))
					return true;
			}
			return false;
		}

	}

	/**
	 * An ImageJ 1.x compatible {@link Progress}.
	 */
	private static class ProgressAdapter implements Progress {
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
	}

	/**
	 * A fall-back adapter to the previous updater version (in case of version skew).
	 */
	private static class LegacyUpdater implements Adapter {
		final Class<?> filesCollectionClass, fileObjectClass, checksummerClass, progressClass, stderrProgressClass;
		final Constructor<?> filesCollectionConstructor, stderrProgressConstructor, checksummerConstructor;
		final Method updateFromLocal, sort, getLocalFilename, getPlatforms, isForPlatform;

		{
			final ClassLoader loader = getClass().getClassLoader();
			try {
				filesCollectionClass = loader.loadClass("imagej.updater.core.FilesCollection");
				filesCollectionConstructor = filesCollectionClass.getConstructor(File.class);
				sort = filesCollectionClass.getMethod("sort");

				fileObjectClass = loader.loadClass("imagej.updater.core.FileObject");
				getLocalFilename = fileObjectClass.getMethod("getLocalFilename", Boolean.TYPE);
				getPlatforms = fileObjectClass.getMethod("getPlatforms");
				isForPlatform = fileObjectClass.getMethod("isForPlatform", String.class);

				progressClass = loader.loadClass("imagej.updater.util.Progress");
				stderrProgressClass = loader.loadClass("imagej.updater.util.StderrProgress");
				stderrProgressConstructor = stderrProgressClass.getConstructor();

				checksummerClass = loader.loadClass("imagej.updater.core.Checksummer");
				checksummerConstructor = checksummerClass.getConstructor(filesCollectionClass, progressClass);
				updateFromLocal = checksummerClass.getMethod("updateFromLocal");
			} catch (Throwable t) {
				if (t instanceof RuntimeException) {
					throw (RuntimeException) t;
				}
				throw new RuntimeException(t);
			}
		}

		@Override
		public void getFileList(Collection<String> list, File ijDir, String... platforms) {
			try {
				final Object files = filesCollectionConstructor.newInstance(ijDir);
				final Object progress = stderrProgressConstructor.newInstance();
				final Object checksummer = checksummerConstructor.newInstance(files, progress);

				updateFromLocal.invoke(checksummer);
				sort.invoke(files);
				@SuppressWarnings("unchecked")
				final Iterable<Object> iterable = (Iterable<Object>) files;
				for (final Object file : iterable) {
					if (isForPlatforms(file, platforms)) {
						final String path = (String) getLocalFilename.invoke(file, false);
						list.add(path);
					}
				}
			} catch (Throwable t) {
				if (t instanceof RuntimeException) {
					throw (RuntimeException) t;
				}
				throw new RuntimeException(t);
			}
		}

		private boolean isForPlatforms(final Object file, String... platforms) {
			try {
				@SuppressWarnings("unchecked")
				final Iterable<String> filePlatforms = (Iterable<String>) getPlatforms.invoke(file);
				if (platforms.length == 0 || !filePlatforms.iterator().hasNext())
					return true;
				for (final String platform : platforms) {
					final Boolean matches = (Boolean) isForPlatform.invoke(file, platform);
					if (matches) {
						return true;
					}
				}
				return false;
			} catch (Throwable t) {
				if (t instanceof RuntimeException) {
					throw (RuntimeException) t;
				}
				throw new RuntimeException(t);
			}
		}
	}

	public void addDefaultFiles() throws IOException {
		boolean logToStdout = IJ.getInstance() == null;
		if (logToStdout) System.out.println("Writing files");
		else IJ.showStatus("Writing files");
		int count = 0;
		for (String fileName : files) {
			addFile(fileName, false);
			if (logToStdout) System.out.print(".");
			else IJ.showProgress(count++, files.size());
		}
		if (logToStdout) System.out.println();
	}

	private void getJREFiles(String... platforms) throws IOException {
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

					if ("macosx-java3d".equals(dir)) {
						dir = "java/" + dir + "/Home";
						if (new File(ijDir, dir).isDirectory())
							directories.add(dir);
						continue;
					}

					dir = getNewestJRE("java/" + dir);
					if (dir == null) {
						System.err.println("No JRE found for platform '" + platform + "'");
						System.exit(1);
					}
				}
				directories.add(dir);
			}

		for (final String dir : directories)
			getFilesInDirectory(dir);
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

	private boolean getFilesInDirectory(String dirName) throws IOException {
		final File[] list = new File(ijDir, dirName).listFiles(new NoHiddenFiles());
		if (list == null)
			return false;
		boolean result = true;
		for (final File file : list)
			if (file.isDirectory())
				result = getFilesInDirectory(dirName + "/" + file.getName()) && result;
			else if (file.isFile())
				result = files.add(dirName + "/" + file.getName()) && result;
		return result;
	}

	public boolean addFile(String fileName, boolean executable) throws IOException {
		if (fileName.equals("ImageJ-macosx") || fileName.equals("ImageJ-tiger"))
			fileName = "Contents/MacOS/" + fileName;
		File file = new File(ijDir, fileName);
		if (!file.exists())
			return false;
		try {
			putNextEntry(prefix + fileName, executable || file.canExecute(), (int)file.length());
			write(new FileInputStream(file));
			closeEntry();
		} catch (IOException e) {
			if (e.getMessage().startsWith("File name too long"))
				System.err.println("Skipping: " + e.getMessage());
			else
				throw e;
		}
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
		String prefix = null;
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
			else if (args[i].startsWith("--prefix=")) {
				prefix = args[i].substring("--prefix=".length());
				if (!prefix.endsWith("/"))
					prefix += "/";
			}
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
			System.err.println("Usage: Package_Maker [--platform=<platform>[,<platform>]] [--jre] [--prefix=<directory>] <filename>");
			System.exit(1);
		}
		String path = args[i];

		if (prefix != null)
			packager.setPrefix(prefix);

		try {
			packager.initialize(includeJRE, platforms);
			packager.open(new FileOutputStream(path));
			packager.addDefaultFiles();
			packager.close();
		}
		catch (Exception e) {
			e.printStackTrace();
			System.err.println("Error writing " + path);
			System.exit(1);
		}
	}
}