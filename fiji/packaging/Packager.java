package fiji.packaging;

import fiji.updater.logic.Checksummer;
import fiji.updater.logic.PluginCollection;
import fiji.updater.logic.PluginObject;

import fiji.updater.util.Progress;
import fiji.updater.util.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class Packager {
	protected PluginCollection plugins;
	protected Progress progress;
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

	public void initialize(Progress progress) {
		this.progress = progress;
		plugins = new PluginCollection();
		Checksummer checksummer = new Checksummer(plugins, progress);
		checksummer.updateFromLocal();
	}

	protected void addDefaultFiles() throws IOException {
		if (progress != null) {
			progress.setTitle("Packaging");
			count = 0;
			total = 4 + plugins.size();
		}
		addFile("db.xml.gz", false, progress);
		// Maybe ImageJ or ImageJ.exe exist?
		addFile("ImageJ", true, progress);
		addFile("ImageJ.exe", true, progress);
		addFile("Contents/Info.plist", false, progress);
		plugins.sort();
		for (PluginObject plugin : plugins)
			addFile(plugin.filename, isLauncher(plugin.filename), progress);
		if (progress != null)
			progress.done();
	}

	protected boolean addFile(String fileName, boolean executable, Progress progress) throws IOException {
		count++;
		if (fileName.equals("ImageJ-macosx") || fileName.equals("ImageJ-tiger"))
			fileName = "Contents/MacOS/" + fileName;
		File file = new File(Util.prefix(fileName));
		if (!file.exists())
			return false;
		if (progress != null) {
			progress.addItem(fileName);
			progress.setCount(count, total);
		}
		putNextEntry("Fiji.app/" + fileName, executable, (int)file.length());
		write(new FileInputStream(file));
		closeEntry();
		if (progress != null)
			progress.itemDone(fileName);
		return true;
	}

	protected static boolean isLauncher(String fileName) {
		if (fileName.startsWith("Fiji.app/"))
			fileName = fileName.substring(9);
		if (fileName.startsWith("Contents/MacOS/"))
			fileName = fileName.substring(15);
		if (fileName.endsWith(".exe"))
			fileName = fileName.substring(0, fileName.length() - 4);
		return fileName.equals("ImageJ") || fileName.equals("fiji") ||
			fileName.startsWith("ImageJ-") || fileName.startsWith("fiji-");
	}
}