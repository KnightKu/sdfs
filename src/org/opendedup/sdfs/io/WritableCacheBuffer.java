/*******************************************************************************
 * Copyright (C) 2016 Sam Silverberg sam.silverberg@gmail.com	
 *
 * This file is part of OpenDedupe SDFS.
 *
 * OpenDedupe SDFS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * OpenDedupe SDFS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package org.opendedup.sdfs.io;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.opendedup.collections.DataArchivedException;
import org.opendedup.collections.HashtableFullException;
import org.opendedup.collections.InsertRecord;
import org.opendedup.collections.LongByteArrayMap;
import org.opendedup.hashing.AbstractHashEngine;
import org.opendedup.hashing.Finger;
import org.opendedup.hashing.HashFunctionPool;
import org.opendedup.hashing.VariableHashEngine;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.servers.HCServiceProxy;
import org.opendedup.util.StringUtils;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Longs;

/**
 * 
 * @author annesam WritableCacheBuffer is used to store written data for later
 *         writing and reading by the DedupFile. WritableCacheBuffers are
 *         evicted from the file system based LRU. When a writable cache buffer
 *         is evicted it is then written to the dedup chunk service
 */
public class WritableCacheBuffer implements DedupChunkInterface, Runnable {

	private ByteBuffer buf = null;
	private boolean dirty = false;

	private long endPosition = 0;
	// private int currentLen = 0;
	private int length;
	private long position;
	private boolean newChunk = false;
	private boolean writable = false;
	private int doop = 0;
	private int bytesWritten = 0;
	private SparseDedupFile df;
	private final ReentrantLock lock = new ReentrantLock();
	private boolean closed = true;
	private boolean flushing = false;
	boolean rafInit = false;
	int prevDoop = 0;
	private boolean batchprocessed;
	private boolean batchwritten;
	private boolean reconstructed;
	private boolean hlAdded = false;
	private boolean direct = false;
	private List<HashLocPair> ar = new ArrayList<HashLocPair>();
	int sz;
	private static SynchronousQueue<Runnable> lworksQueue = null;
	private static RejectedExecutionHandler lexecutionHandler = new BlockPolicy();
	private static ThreadPoolExecutor lexecutor = null;
	private static ThreadPoolExecutor executor = null;
	private static SynchronousQueue<Runnable> worksQueue = null;
	private static int maxTasks = (HashFunctionPool.max_hash_cluster) * Main.writeThreads;

	static {
		if(!Main.chunkStoreLocal) {
			if (maxTasks > 120)
				maxTasks = 120;
			SDFSLogger.getLog().info("Maximum Read Threads is " + maxTasks);
			worksQueue = new SynchronousQueue<Runnable>();
			executor = new ThreadPoolExecutor(maxTasks, maxTasks, 0L, TimeUnit.SECONDS, worksQueue, lexecutionHandler);
		} else {
			worksQueue = new SynchronousQueue<Runnable>();
			executor = new ThreadPoolExecutor(Main.writeThreads, Main.writeThreads, 0L, TimeUnit.SECONDS, worksQueue, lexecutionHandler);
		}
		lworksQueue = new SynchronousQueue<Runnable>();
		lexecutor = new ThreadPoolExecutor(Main.writeThreads, Main.writeThreads, 0L, TimeUnit.SECONDS, lworksQueue,
				lexecutionHandler);
	}

	public WritableCacheBuffer(long startPos, int length, SparseDedupFile df, List<HashLocPair> ar,
			boolean reconstructed) throws IOException {
		this.length = length;
		this.position = startPos;
		this.newChunk = true;
		this.ar = ar;
		this.df = df;
		this.reconstructed = reconstructed;
		buf = ByteBuffer.wrap(new byte[Main.CHUNK_LENGTH]);
		if (this.df.bdb.getVersion() == 3) {
			this.direct = true;
		}
		// this.currentLen = 0;
		this.setLength(Main.CHUNK_LENGTH);
		this.endPosition = this.getFilePosition() + this.getLength();
		this.setWritable(true);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#getBytesWritten()
	 */
	@Override
	public int getBytesWritten() {
		return bytesWritten;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#getDedupFile()
	 */
	@Override
	public DedupFile getDedupFile() {
		return this.df;
	}

	public WritableCacheBuffer(DedupChunkInterface dk, DedupFile df) throws IOException {
		this.position = dk.getFilePosition();
		this.length = dk.getLength();
		this.newChunk = dk.isNewChunk();
		this.prevDoop = dk.getPrevDoop();
		this.reconstructed = dk.getReconstructed();
		this.ar = dk.getFingers();
		this.df = (SparseDedupFile) df;
		this.setLength(Main.CHUNK_LENGTH);
		this.endPosition = this.getFilePosition() + this.getLength();
		this.setWritable(true);

		if (this.df.bdb.getVersion() == 3) {
			this.direct = true;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#sync()
	 */
	@Override
	public boolean sync() throws IOException {

		return false;
	}

	public byte[] getReadChunk(int startPos, int len) throws IOException, BufferClosedException, DataArchivedException {

		if (SDFSLogger.isDebug())
			SDFSLogger.getLog().debug("reading " + df.getMetaFile().getPath() + " df=" + df.getGUID() + " fpos="
					+ this.position + " start=" + startPos + " len=" + len);
		this.lock.lock();
		try {
			if (this.closed)
				throw new BufferClosedException("Buffer Closed");
			if (this.flushing)
				throw new BufferClosedException("Buffer Flushing");
			try {
				this.initBuffer();
			} catch (InterruptedException e) {
				throw new IOException(e);
			}
			byte[] dd = new byte[len];
			buf.position(startPos);
			buf.get(dd);
			if (SDFSLogger.isDebug()) {
				if (df.getMetaFile().getPath().endsWith(".vmx") || df.getMetaFile().getPath().endsWith(".vmx~")) {
					SDFSLogger.getLog().debug("###### In wb read Text of VMX=" + df.getMetaFile().getPath() + "="
							+ new String(dd, "UTF-8"));
				}
			}
			return dd;
		} finally {
			this.lock.unlock();
		}

	}

	public void cacheChunk() throws IOException, InterruptedException, DataArchivedException {
		if (this.buf == null) {
			this.hlAdded = false;
			if (HashFunctionPool.max_hash_cluster > 1) {

				final ArrayList<Shard> cks = new ArrayList<Shard>();
				int i = 0;
				// long fp = this.position;

				for (HashLocPair p : ar) {

					if (Longs.fromByteArray(p.hashloc) != 0) {
						Shard sh = new Shard();
						sh.hash = p.hash;
						sh.hashloc = p.hashloc;
						sh.pos = p.pos;
						sh.nlen = p.nlen;
						sh.offset = p.offset;
						sh.len = p.len;
						sh.direct = this.direct;
						sh.apos = i;
						cks.add(i, sh);
					} else
						break;
					i++;
				}
				if (Main.chunkStoreLocal) {
					ShardReader r = new ShardReader();
					r.shards = cks;
					r.direct = this.direct;
					r.cache = true;
					r.read();
				} else {
					sz = cks.size();
					AsyncChunkReadActionListener l = new AsyncChunkReadActionListener() {

						@Override
						public void commandException(Exception e) {
							this.incrementAndGetDNEX();
							synchronized (this) {
								this.notifyAll();
							}

						}

						@Override
						public void commandResponse(Shard result) {
							cks.get(result.apos).ck = result.ck;
							if (this.incrementandGetDN() >= sz) {

								synchronized (this) {
									this.notifyAll();
								}
							}
						}

						@Override
						public void commandArchiveException(DataArchivedException e) {
							this.incrementAndGetDNEX();
							this.setDAR(e);

							synchronized (this) {
								this.notifyAll();
							}

						}

					};
					for (Shard sh : cks) {
						sh.l = l;
						sh.cache = true;
						sh.direct = direct;
						executor.execute(sh);
					}
					int wl = 0;
					int al = 0;

					while (l.getDN() < sz && l.getDNEX() == 0) {
						if (al == 30) {
							int nt = wl / 1000;
							SDFSLogger.getLog()
									.debug("Slow io, waited [" + nt + "] seconds for all reads to complete.");
							al = 0;
						}

						if (l.getDAR() != null) {
							throw l.getDAR();
						}
						if (l.getDNEX() > 0)
							throw new IOException("error while reading data");
						synchronized (l) {
							l.wait(1000);
						}
						wl += 1000;
						al++;
					}
					if (l.getDAR() != null) {
						throw l.getDAR();
					}
					if (l.getDNEX() > 0) {
						throw new IOException("error while getting blocks " + l.getDNEX() + " errors found");
					}
					if (l.getDN() < sz) {
						throw new IOException("thread timed out before read was complete ");
					}
				}

			}
		}
	}

	int tries = 0;

	private void initBuffer() throws IOException, InterruptedException, DataArchivedException {
		if (this.buf == null) {
			this.hlAdded = false;
			if (HashFunctionPool.max_hash_cluster > 1) {
				this.buf = ByteBuffer.wrap(new byte[Main.CHUNK_LENGTH]);

				final ArrayList<Shard> cks = new ArrayList<Shard>();
				int i = 0;
				// long fp = this.position;

				for (HashLocPair p : ar) {

					if (Longs.fromByteArray(p.hashloc) != 0) {
						Shard sh = new Shard();
						sh.hash = p.hash;
						sh.hashloc = p.hashloc;
						sh.direct = this.direct;
						sh.pos = p.pos;
						sh.nlen = p.nlen;
						sh.offset = p.offset;
						sh.len = p.len;
						sh.apos = i;
						cks.add(i, sh);
					} else
						break;
					i++;
				}

				sz = cks.size();
				AsyncChunkReadActionListener l = new AsyncChunkReadActionListener() {

					@Override
					public void commandException(Exception e) {
						SDFSLogger.getLog().error("error getting block", e);
						this.incrementAndGetDNEX();
						synchronized (this) {
							this.notifyAll();
						}

					}

					@Override
					public void commandResponse(Shard result) {
						cks.get(result.apos).ck = result.ck;
						if (this.incrementandGetDN() >= sz) {

							synchronized (this) {
								this.notifyAll();
							}
						}
					}

					@Override
					public void commandArchiveException(DataArchivedException e) {
						this.incrementAndGetDNEX();
						this.setDAR(e);

						synchronized (this) {
							this.notifyAll();
						}

					}

				};
				for (Shard sh : cks) {
					sh.l = l;
					sh.cache = false;
					sh.direct = this.direct;
					executor.execute(sh);
				}
				int wl = 0;
				int tm = 1000;
				int al = 0;

				while (l.getDN() < sz && l.getDNEX() == 0) {
					if (al == 30) {
						int nt = wl / 1000;
						SDFSLogger.getLog().debug("Slow io, waited [" + nt + "] seconds for all reads to complete.");
						al = 0;
					}
					if (Main.readTimeoutSeconds > 0 && wl > (Main.readTimeoutSeconds * tm)) {
						int nt = (tm * wl) / 1000;
						throw new IOException("read Timed Out after [" + nt + "] seconds. Expected [" + sz
								+ "] block read but only [" + l.getDN() + "] were completed");
					}
					if (l.getDAR() != null) {
						throw l.getDAR();
					}
					if (l.getDNEX() > 0)
						throw new IOException("error while reading data");
					synchronized (l) {
						l.wait(1000);
					}
					wl += 1000;
					al++;
				}
				if (l.getDAR() != null) {
					throw l.getDAR();
				}
				if (l.getDNEX() > 0) {
					
						throw new IOException("error while getting blocks " + l.getDNEX() + " errors found");

				}
				if (l.getDN() < sz) {
					throw new IOException("thread timed out before read was complete ");
				}
				buf.position(0);
				for (Shard sh : cks) {
					if (sh.pos == -1) {
						try {
							buf.put(sh.ck);
						} catch (Exception e) {
							// SDFSLogger.getLog().info("pos = " + this.position
							// + "ck sz=" + sh.ck.length + " hcb sz=" +
							// hcb.position() + " cks sz=" +cks.size() + " len="
							// + (hcb.position() +sh.ck.length));
							throw new IOException(e);
						}
					} else {
						try {
							buf.position(sh.pos);
							buf.put(sh.ck, sh.offset, sh.nlen);

						} catch (Exception e) {
							String hp = StringUtils.getHexString(sh.hash);
							SDFSLogger.getLog()
									.error("hash=" + hp + " pos = " + this.position + " ck nlen=" + sh.nlen
											+ " ckoffset=" + sh.offset + " cklen=" + sh.ck.length + " hcbpos="
											+ buf.position() + " ckslen=" + sh.len + " len=" + (buf.capacity()));
							throw new IOException(e);
						}
					}
				}

			} else {
				this.buf = ByteBuffer
						.wrap(HCServiceProxy.fetchChunk(this.ar.get(0).hash, this.ar.get(0).hashloc, direct));

			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#capacity()
	 */
	@Override
	public int capacity() {

		return Main.CHUNK_LENGTH;
	}

	public void setAR(List<HashLocPair> al) {
		this.ar = al;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#getEndPosition()
	 */
	@Override
	public long getEndPosition() {
		return endPosition;
	}

	private void writeBlock(byte[] b, int pos) throws IOException, DataArchivedException {
		try {
			this.initBuffer();
			buf.position(pos);
			buf.put(b);
			this.hlAdded = false;
			this.dirty = true;
		} catch (InterruptedException e) {
			throw new IOException(e);
		}

	}

	public HashLocPair getPair(int pos) {
		for (HashLocPair h : ar) {
			int ep = h.pos + h.nlen;
			if (pos >= h.pos && pos < ep) {
				return h;
			}
		}
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#write(byte[], int)
	 */

	@Override
	public void write(byte[] b, int pos) throws BufferClosedException, IOException, DataArchivedException {
		if (SDFSLogger.isDebug()) {
			SDFSLogger.getLog().debug("writing " + df.getMetaFile().getPath() + "df=" + df.getGUID() + "fpos="
					+ this.position + " pos=" + pos + " len=" + b.length);
			if (df.getMetaFile().getPath().endsWith(".vmx") || df.getMetaFile().getPath().endsWith(".vmx~")) {
				SDFSLogger.getLog()
						.debug("###### In wb Text of VMX=" + df.getMetaFile().getPath() + "=" + new String(b, "UTF-8"));
			}
		}
		this.lock.lock();
		try {
			if (this.closed)
				throw new BufferClosedException("Buffer Closed while writing");
			if (this.flushing)
				throw new BufferClosedException("Buffer Flushing");

			/*
			 * if(pos != 0) SDFSLogger.getLog().info("start at " + pos);
			 * if(b.length != this.capacity()) SDFSLogger.getLog().info(
			 * "!capacity " + b.length);
			 */
			if (pos == 0 && b.length == Main.CHUNK_LENGTH) {
				this.buf = ByteBuffer.wrap(b);

				this.setDirty(true);
			} else {

				if (this.ar.size() >= LongByteArrayMap.MAX_ELEMENTS_PER_AR) {

					this.writeBlock(b, pos);
					this.ar = new ArrayList<HashLocPair>();
				} else if (this.buf == null && this.reconstructed && HashFunctionPool.max_hash_cluster > 1) {
					// SDFSLogger.getLog().info("poop " + b.length + " pos=" +
					// pos + "_spos=" + _spos + " bpos=" +bpos );
					if (b.length < VariableHashEngine.minLen) {
						HashLocPair p = new HashLocPair();
						AbstractHashEngine eng = HashFunctionPool.borrowObject();
						try {
							p.hash = eng.getHash(b);
							InsertRecord rec = HCServiceProxy.writeChunk(p.hash, b);

							p.hashloc = rec.getHashLocs();
							p.len = b.length;
							p.nlen = b.length;
							p.offset = 0;
							p.pos = pos;
							int dups = 0;
							if (!rec.getInserted())
								dups = b.length;
							df.mf.getIOMonitor().addVirtualBytesWritten(b.length, true);
							df.mf.getIOMonitor().addActualBytesWritten(b.length - dups, true);
							df.mf.getIOMonitor().addDulicateData(dups, true);
							this.prevDoop += dups;
							SparseDataChunk.insertHashLocPair(ar, p);
							this.hlAdded = true;

							/*
							 * HashLocPair _h =null;
							 * 
							 * for(HashLocPair h : ar) { if(_h!=null && h.pos !=
							 * (_h.pos + _h.nlen)) { SDFSLogger.getLog().info(
							 * "data mismatch"); SDFSLogger.getLog().info(_h);
							 * SDFSLogger.getLog().info(h); } _h=h; }
							 */

						} catch (HashtableFullException e) {
							SDFSLogger.getLog().error("unable to write with accelerator", e);
							throw new IOException(e);
						} finally {
							HashFunctionPool.returnObject(eng);
						}
					} else {
						this.wm(b, pos);
						/*
						 * HashLocPair _h =null;
						 * 
						 * for(HashLocPair h : ar) { if(_h!=null && h.pos !=
						 * (_h.pos + _h.nlen)) { SDFSLogger.getLog().info(
						 * "data mismatch"); SDFSLogger.getLog().info(_h);
						 * SDFSLogger.getLog().info(h); } _h=h; }
						 */
					}
				} else {
					// SDFSLogger.getLog().info("writing at " + pos + " recon="
					// + this.reconstructed + " sz=" + this.ar.size());
					this.writeBlock(b, pos);
				}
			}

			this.bytesWritten = this.bytesWritten + b.length;
		} finally {
			this.lock.unlock();
		}
	}

	public void copyExtent(HashLocPair p) throws IOException, BufferClosedException, DataArchivedException {
		if (this.closed)
			throw new BufferClosedException("Buffer Closed while writing");
		if (this.flushing)
			throw new BufferClosedException("Buffer Flushing");
		this.lock.lock();
		try {
			if (!this.isDirty() && this.buf != null) {
				this.buf = null;
			}
			if (this.buf != null || this.ar.size() >= LongByteArrayMap.MAX_ELEMENTS_PER_AR) {
				if (this.ar.size() >= LongByteArrayMap.MAX_ELEMENTS_PER_AR)
					SDFSLogger.getLog()
							.debug("copy extent Chuck Array Size greater than " + LongByteArrayMap.MAX_ELEMENTS_PER_AR
									+ " at " + (this.getFilePosition() + p.pos) + " for file " + this.df.mf.getPath());
				byte[] b = HCServiceProxy.fetchChunk(p.hash, p.hashloc, direct);
				ByteBuffer bf = ByteBuffer.wrap(b);
				byte[] z = new byte[p.nlen];
				bf.position(p.offset);
				bf.get(z);
				this.writeBlock(z, p.pos);
			} else {
				try {
					this.reconstructed = true;
					this.hlAdded = true;
					SparseDataChunk.insertHashLocPair(ar, p);
				} catch (Throwable e) {
					df.errOccured = true;
					throw new IOException(e);
				}
			}
		} finally {
			this.lock.unlock();
		}
	}

	private void wm(byte[] b, int pos) throws IOException {
		VariableHashEngine hc = (VariableHashEngine) HashFunctionPool.borrowObject();

		try {
			List<Finger> fs = hc.getChunks(b);
			AsyncChunkWriteActionListener l = new AsyncChunkWriteActionListener() {

				@Override
				public void commandException(Finger result, Throwable e) {
					this.incrementAndGetDNEX();
					SDFSLogger.getLog().error("Error while getting hash", e);
					this.incrementandGetDN();

					synchronized (this) {
						this.notifyAll();
					}
				}

				@Override
				public void commandResponse(Finger result) {
					int _dn = this.incrementandGetDN();
					if (_dn >= this.getMaxSz()) {
						synchronized (this) {
							this.notifyAll();
						}
					}
				}

				@Override
				public void commandArchiveException(DataArchivedException e) {
					this.incrementAndGetDNEX();
					this.dar = e;
					SDFSLogger.getLog().error("Data has been archived", e);
					this.incrementandGetDN();

					synchronized (this) {
						this.notifyAll();
					}

				}

			};
			l.setMaxSize(fs.size());
			Finger.FingerPersister fp = new Finger.FingerPersister();
			fp.l = l;
			fp.fingers = fs;
			fp.dedup = df.mf.isDedup();
			lexecutor.execute(fp);
			int wl = 0;
			int tm = 1000;

			int al = 0;
			while (l.getDN() < fs.size() && l.getDNEX() == 0) {
				if (al == 60) {
					int nt = wl / 1000;
					SDFSLogger.getLog().warn("Slow io, waited [" + nt + "] seconds for all writes to complete.");
					al = 0;
				}
				if (Main.writeTimeoutSeconds > 0 && wl > (Main.writeTimeoutSeconds * tm)) {
					int nt = (tm * wl) / 1000;
					df.toOccured = true;
					throw new IOException("Write Timed Out after [" + nt + "] seconds. Expected [" + fs.size()
							+ "] block writes but only [" + l.getDN() + "] were completed");
				}
				if (l.dar != null)
					throw l.dar;
				if (l.getDNEX() > 0) {
					throw new IOException("Unable to read shard");
				}
				synchronized (l) {
					l.wait(tm);
				}
				al++;
				wl += tm;
			}
			if (l.dar != null)
				throw l.dar;
			if (l.getDN() < fs.size()) {
				df.toOccured = true;
				throw new IOException("Write Timed Out expected [" + fs.size() + "] but got [" + l.getDN() + "]");
			}
			if (l.getDNEX() > 0)
				throw new IOException("Write Failed because unable to read shard");
			for (Finger f : fs) {
				HashLocPair p = new HashLocPair();
				try {
					p.hash = f.hash;
					p.hashloc = f.hl.getHashLocs();
					p.len = f.len;
					p.offset = 0;
					p.nlen = f.len;
					p.pos = pos;
					pos += f.len;
					int dups = 0;
					if (!f.hl.getInserted())
						dups = f.len;
					df.mf.getIOMonitor().addVirtualBytesWritten(f.len, true);
					df.mf.getIOMonitor().addActualBytesWritten(f.len - dups, true);
					df.mf.getIOMonitor().addDulicateData(dups, true);
					this.prevDoop += dups;
					SparseDataChunk.insertHashLocPair(ar, p);
				} catch (Throwable e) {
					SDFSLogger.getLog().warn("unable to write object finger", e);
					throw e;
					// SDFSLogger.getLog().info("this chunk size is "
					// + f.chunk.length);
				}
			}
			this.hlAdded = true;
		} catch (Throwable e) {
			df.errOccured = true;
			throw new IOException(e);
		} finally {
			HashFunctionPool.returnObject(hc);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#truncate(int)
	 */
	@Override
	public void truncate(int len) throws BufferClosedException {
		this.lock.lock();
		try {

			if (this.closed)
				throw new BufferClosedException("Buffer Closed");

			this.destroy();
			this.setDirty(true);
		} finally {
			this.lock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#isDirty()
	 */
	@Override
	public boolean isDirty() {
		this.lock.lock();
		try {
			return dirty;
		} finally {
			this.lock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#setDirty(boolean)
	 */
	@Override
	public void setDirty(boolean dirty) {
		this.lock.lock();
		this.dirty = dirty;
		this.lock.unlock();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#toString()
	 */
	@Override
	public String toString() {
		return this.hashCode() + ":" + this.getFilePosition() + ":" + this.getLength() + ":" + this.getEndPosition();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#open()
	 */
	@Override
	public void open() {
		try {
			this.lock.lock();
			if (this.flushing) {
				this.df.removeBufferFromFlush(this);
			}
			this.closed = false;
			this.flushing = false;
		} catch (Exception e) {
			SDFSLogger.getLog().fatal("Error while opening", e);
			throw new IllegalArgumentException("error");
		} finally {
			this.lock.unlock();
		}
	}

	public void flush() throws BufferClosedException {
		this.lock.lock();
		try {

			if (this.flushing) {
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog()
							.debug("cannot flush buffer at pos " + this.getFilePosition() + " already flushing");
				throw new BufferClosedException("Buffer Closed");

			}
			if (this.closed) {
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug("cannot flush buffer at pos " + this.getFilePosition() + " closed");
				throw new BufferClosedException("Buffer Closed");
			}
			this.flushing = true;
			if (this.isDirty() || this.isHlAdded()) {
				if (Main.chunkStoreLocal) {
					this.df.putBufferIntoFlush(this);
					lexecutor.execute(this);
				} else {
					SparseDedupFile.pool.execute(this);
					this.df.putBufferIntoFlush(this);
				}
			}
		} finally {
			this.lock.unlock();
		}
	}

	public boolean isClosed() {
		this.lock.lock();
		try {
			return this.closed;
		} finally {
			this.lock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#close()
	 */
	@Override
	public void close() throws IOException {
		this.lock.lock();
		try {
			if (!this.flushing) {
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug("#### " + this.getFilePosition() + " not flushing ");
			} else if (this.closed) {
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug(this.getFilePosition() + " already closed");
			} else if (this.dirty || this.hlAdded) {
				this.df.writeCache(this);
				this.closed = true;
				this.flushing = false;
				this.dirty = false;
				this.hlAdded = false;
			} else {
				this.closed = true;
				this.flushing = false;
			}
		} catch (Exception e) {
			throw new IOException(e);
		} finally {
			try {
				df.removeBufferFromFlush(this);
			} catch (Exception e) {
			}
			this.lock.unlock();

		}
	}

	public void startClose() {
		this.lock.lock();
		this.batchprocessed = true;
	}

	public boolean isBatchProcessed() {
		return this.batchprocessed;
	}

	public void endClose() throws IOException {
		try {
			if (!this.flushing) {
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug("####" + this.getFilePosition() + " not flushing");
			} else if (this.closed) {
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug(this.getFilePosition() + " already closed");
			} else {

				this.df.writeCache(this);
				df.removeBufferFromFlush(this);
				this.closed = true;
				this.flushing = false;
			}

		} catch (Exception e) {
			throw new IOException(e);
		} finally {
			this.batchprocessed = false;
			this.lock.unlock();

		}
	}

	public byte[] getFlushedBuffer() throws BufferClosedException {
		this.lock.lock();
		try {
			if (this.closed) {
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug(this.getFilePosition() + " already closed");
				throw new BufferClosedException("Buffer Closed");
			}
			if (!this.flushing) {
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug(this.getFilePosition() + " not flushed");
				throw new BufferClosedException("Buffer not flushed");
			}
			if (this.buf == null)
				SDFSLogger.getLog().debug(this.getFilePosition() + " buffer is null");
			byte[] b = new byte[this.buf.capacity()];
			System.arraycopy(this.buf.array(), 0, b, 0, b.length);
			return b;
		} finally {
			this.lock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#persist()
	 */
	@Override
	public void persist() {
		try {
			this.lock.lock();
			this.df.writeCache(this);
			this.closed = true;
		} catch (Exception e) {
			SDFSLogger.getLog().fatal("Error while closing", e);
			throw new IllegalArgumentException("error while closing " + e.toString());
		} finally {
			this.lock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#destroy()
	 */
	@Override
	public void destroy() {

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#isPrevDoop()
	 */
	@Override
	public int getPrevDoop() {
		return prevDoop;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#setPrevDoop(boolean)
	 */
	@Override
	public void setPrevDoop(int prevDoop) {
		this.prevDoop = prevDoop;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#hashCode()
	 */
	@Override
	public int hashCode() {
		this.lock.lock();
		try {
			HashFunction hf = Hashing.murmur3_128(6442);
			return hf.hashBytes(buf.array()).asInt();
		} finally {
			this.lock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#getLength()
	 */
	@Override
	public int getLength() {
		return this.length;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#getFilePosition()
	 */
	@Override
	public long getFilePosition() {
		return this.position;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#setLength(int)
	 */
	@Override
	public void setLength(int length) {
		this.length = length;

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#isNewChunk()
	 */
	@Override
	public boolean isNewChunk() {
		return this.newChunk;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#setNewChunk(boolean)
	 */
	@Override
	public void setNewChunk(boolean newChunk) {
		this.newChunk = newChunk;

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#setWritable(boolean)
	 */
	@Override
	public void setWritable(boolean writable) {
		this.writable = writable;

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#isWritable()
	 */
	@Override
	public boolean isWritable() {
		return this.writable;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#setDoop(boolean)
	 */
	@Override
	public void setDoop(int doop) {
		this.doop = doop;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.sdfs.io.CacheBufferInterface2#isDoop()
	 */
	@Override
	public int getDoop() {
		return this.doop;
	}

	public boolean isBatchwritten() {
		return batchwritten;
	}

	public void setBatchwritten(boolean batchwritten) {
		this.batchwritten = batchwritten;
	}

	public static class BlockPolicy implements RejectedExecutionHandler {

		/**
		 * Creates a <tt>BlockPolicy</tt>.
		 */
		public BlockPolicy() {
		}

		/**
		 * Puts the Runnable to the blocking queue, effectively blocking the
		 * delegating thread until space is available.
		 * 
		 * @param r
		 *            the runnable task requested to be executed
		 * @param e
		 *            the executor attempting to execute this task
		 */
		public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
			try {
				e.getQueue().put(r);
			} catch (Exception e1) {
				SDFSLogger.getLog()
						.error("Work discarded, thread was interrupted while waiting for space to schedule: {}", e1);
			}
		}
	}

	public static class Shard implements Runnable {
		public byte[] hash;
		public byte[] hashloc;
		public int len;
		public int pos;
		public int apos;
		public int offset;
		public int nlen;
		public boolean direct;
		public boolean cache;

		public byte[] ck;
		AsyncChunkReadActionListener l;

		@Override
		public void run() {
			try {

				if (cache) {
					HCServiceProxy.cacheData(hash, hashloc,direct);
				} else {
					this.ck = HCServiceProxy.fetchChunk(hash, hashloc, direct);
					l.commandResponse(this);

				}

			} catch (DataArchivedException e) {
				l.commandArchiveException(e);
			} catch (Exception e) {
				l.commandException(e);
			}

		}

	}

	public static class ShardReader {
		List<Shard> shards;
		public boolean cache;
		public boolean direct;

		public void read() throws IOException, DataArchivedException {
			for (Shard s : shards) {
				if (cache) {
					HCServiceProxy.cacheData(s.hash, s.hashloc,direct);
				} else
					s.ck = HCServiceProxy.fetchChunk(s.hash, s.hashloc, direct);

			}

		}

	}

	@Override
	public void run() {
		try {
			this.close();
		} catch (Exception e) {
			df.errOccured = true;
			SDFSLogger.getLog().error("unable to close", e);
		}

	}

	@Override
	public List<HashLocPair> getFingers() {
		// TODO Auto-generated method stub
		Collections.sort(ar);
		return ar;
	}

	@Override
	public boolean getReconstructed() {
		return this.reconstructed;
	}

	public boolean isHlAdded() {
		return hlAdded;
	}

	public void setHlAdded(boolean hlAdded) {
		this.hlAdded = hlAdded;
	}

	@Override
	public void setReconstructed(boolean re) {
		this.reconstructed = re;

	}

}