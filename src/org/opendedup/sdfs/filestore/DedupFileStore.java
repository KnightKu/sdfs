package org.opendedup.sdfs.filestore;

import java.io.IOException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.io.DedupFile;
import org.opendedup.sdfs.io.MetaDataDedupFile;
import org.opendedup.sdfs.io.SparseDedupFile;

/**
 * 
 * @author Sam Silverberg
 * 
 *         DedupFileStore is a static class used to store, return, and clone
 *         dedup file maps. All dedup files should be accessed through this
 *         class exclusively.
 * 
 */
public class DedupFileStore {

	private static boolean closing = false;
	// private static ReentrantLock clock = new ReentrantLock();
	private static OpenFileMonitor openFileMonitor = null;
	/*
	 * stores open files in an LRU map. Files will be evicted based on the
	 * maxOpenFiles parameter
	 */
	private static ConcurrentHashMap<String, DedupFile> openFile = new ConcurrentHashMap<String, DedupFile>();
	/*
	 * Spawns to open file monitor. The openFile monitor is used to evict open
	 * files from the openFile hashmap.
	 */
	static {
		if (Main.maxInactiveFileTime > 0 && !Main.blockDev) {
			openFileMonitor = new OpenFileMonitor(10000,
					Main.maxInactiveFileTime);
		} else if (Main.blockDev) {
			// openFileMonitor = new OpenFileMonitor(1000,
			// Main.maxInactiveFileTime);
		}
	}

	/**
	 * 
	 * @param mf
	 *            the metadata dedup file to get the DedupFile for. If no dedup
	 *            file map exists, one is created.
	 * @return the dedup file map associated with the MetaDataDedupFile
	 * @throws IOException
	 */
	private static ReentrantLock getDFLock = new ReentrantLock();

	public static void updateDedupFile(MetaDataDedupFile mf) {
		getDFLock.lock();
		try {
			DedupFile df = openFile.get(mf.getDfGuid());
			if (df != null)
				df.setMetaDataDedupFile(mf);
		} finally {
			getDFLock.unlock();
		}
	}

	public static DedupFile getDedupFile(MetaDataDedupFile mf)
			throws IOException {
		getDFLock.lock();
		DedupFile df = null;
		try {
			if (!closing) {
				df = openFile.get(mf.getDfGuid());
				if (df == null) {
					df = new SparseDedupFile(mf);
				}
				return df;
			} else {
				throw new IOException("DedupFileStore is closed");
			}
		} finally {
			getDFLock.unlock();
		}
	}

	public static DedupFile openDedupFile(MetaDataDedupFile mf)
			throws IOException {
		getDFLock.lock();
		DedupFile df = null;
		try {
			if (!closing) {
				df = openFile.get(mf.getDfGuid());
				if (df == null) {
					df = new SparseDedupFile(mf);
					DedupFileStore.openFile.put(df.getGUID(), df);
				}
				return df;
			} else {
				throw new IOException("DedupFileStore is closed");
			}
		} finally {
			getDFLock.unlock();
		}
	}

	/**
	 * Adds a dedup file to the openFile hashmap.
	 * 
	 * @param df
	 *            the dedup file to add to the openfile hashmap
	 * @throws IOException
	 */
	public static void addOpenDedupFiles(DedupFile df) throws IOException {
		if (!closing) {
			if (SDFSLogger.isDebug())
				SDFSLogger.getLog().debug(
						"adding dedupfile " + df.getMetaFile().getPath());
			if (openFile.size() >= Main.maxOpenFiles)
				throw new IOException("maximum number of files reached ["
						+ Main.maxOpenFiles + "]. Too many open files");
			openFile.put(df.getGUID(), df);
			if (SDFSLogger.isDebug())
				SDFSLogger.getLog().debug(
						"dedupfile cache size is " + openFile.size());
		} else {
			throw new IOException("DedupFileStore is closed");
		}
	}

	/**
	 * Clones a dedupFile
	 * 
	 * @param oldmf
	 *            the file to clone
	 * @param newmf
	 *            the location of the, new, cloned dedup file map.
	 * @return the new cloned Dedup file map
	 * @throws IOException
	 */
	public static DedupFile cloneDedupFile(MetaDataDedupFile oldmf,
			MetaDataDedupFile newmf) throws IOException {
		if (!closing) {
			if (oldmf.getDfGuid() == null)
				return null;
			else {
				DedupFile df = openFile.get(oldmf.getDfGuid());
				if (df == null) {
					df = new SparseDedupFile(oldmf);
				}
				try {
					return df.snapshot(newmf, true);
				} catch (Exception e) {
					throw new IOException(e);
				}
			}

		} else {
			throw new IOException("DedupFileStore is closed");
		}
	}

	/**
	 * removes an open dedup file map from the openFile hashmap.
	 * 
	 * @param mf
	 */
	public static void removeOpenDedupFile(String guid) {
		openFile.remove(guid);
	}

	/**
	 * Checks if a file is open
	 * 
	 * @param mf
	 *            the meta file to check if its open.
	 * @return true if open
	 */
	public static boolean fileOpen(MetaDataDedupFile mf) {
		try {
			return openFile.containsKey(mf.getDfGuid());
		} catch (NullPointerException e) {
			return false;
		}
	}

	/**
	 * 
	 * @return the open files at an array.
	 */
	public static DedupFile[] getArray() {
		DedupFile[] dfr = new DedupFile[openFile.size()];
		openFile.values().toArray(dfr);
		return dfr;
	}

	/**
	 * Closes the DedupFileStore, openFiles, and the openFileMonitor.
	 */
	public static void close() {
		closing = true;
		if (SDFSLogger.isDebug())
			SDFSLogger.getLog().debug("Open Files = " + openFile.size());
		if (openFileMonitor != null)
			openFileMonitor.close();
		Object[] dfs = getArray();
		SDFSLogger.getLog().info("closing openfiles of size " + dfs.length);
		for (int i = 0; i < dfs.length; i++) {
			DedupFile df = (DedupFile) dfs[i];
			if (df != null) {
				try {
					df.forceClose();
				} catch (IOException e) {
					if (SDFSLogger.isDebug())
						SDFSLogger.getLog()
								.debug("unable to Close "
										+ df.getMetaFile().getPath(), e);
				}
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug(
							"Closed " + df.getMetaFile().getPath());
			}
		}
	}

	/**
	 * Flushes the write buffers for all open files.
	 */
	public static void flushAllFiles() {
		if (SDFSLogger.isDebug())
			SDFSLogger.getLog().debug(
					"flushing write caches of size " + openFile.size());
		Object[] dfs = getArray();
		for (int i = 0; i < dfs.length; i++) {
			DedupFile df = (DedupFile) dfs[i];
			try {
				df.writeCache();
			} catch (Exception e) {
				SDFSLogger.getLog().warn("DSE Full", e);
			}
		}
		if (SDFSLogger.isDebug())
			SDFSLogger.getLog().debug("write caches flushed");
	}
}
