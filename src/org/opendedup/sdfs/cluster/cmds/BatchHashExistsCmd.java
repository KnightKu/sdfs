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
package org.opendedup.sdfs.cluster.cmds;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.util.List;

import org.jgroups.Message;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.ResponseMode;
import org.jgroups.util.Rsp;
import org.jgroups.util.RspList;
import org.jgroups.util.Util;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.cluster.DSEClientSocket;
import org.opendedup.sdfs.io.HashLocPair;

public class BatchHashExistsCmd implements IOClientCmd {
	List<HashLocPair> hashes;
	boolean exists = false;
	RequestOptions opts = null;

	public BatchHashExistsCmd(List<HashLocPair> hashes) {
		this.hashes = hashes;
	}

	@Override
	public void executeCmd(final DSEClientSocket soc) throws IOException {
		opts = new RequestOptions(ResponseMode.GET_ALL, Main.ClusterRSPTimeout,
				true);
		opts.setFlags(Message.Flag.DONT_BUNDLE);
		opts.setFlags(Message.Flag.NO_FC);
		// opts.setFlags(Message.Flag.OOB);
		opts.setFlags(Message.Flag.NO_TOTAL_ORDER);
		try {
			byte[] ar = Util.objectToByteBuffer(hashes);
			byte[] b = new byte[1 + 4 + ar.length];
			ByteBuffer buf = ByteBuffer.wrap(b);
			buf.put(NetworkCMDS.BATCH_HASH_EXISTS_CMD);
			buf.putInt(ar.length);
			buf.put(ar);
			// List<Address> servers = soc.getServers();
			RspList<Object> lst = soc.disp.castMessage(null, new Message(null,
					null, buf.array()), opts);
			for (HashLocPair p : hashes) {

				if (p != null)
					p.resetHashLoc();
			}
			for (Rsp<Object> rsp : lst) {
				if (rsp.hasException()) {
					SDFSLogger.getLog().error(
							"Batch Hash Exists Exception thrown for "
									+ rsp.getSender());
					throw rsp.getException();
				} else if (rsp.wasSuspected() | rsp.wasUnreachable()) {
					SDFSLogger.getLog().error(
							"Batch Hash Exists Host unreachable Exception thrown for "
									+ rsp.getSender());
				} else {
					if (rsp.getValue() != null) {
						SDFSLogger.getLog().debug(
								"Batch Hash Exists completed for "
										+ rsp.getSender() + " returned="
										+ rsp.getValue());
						@SuppressWarnings("unchecked")
						List<Boolean> rst = (List<Boolean>) rsp.getValue();
						byte id = soc.serverState.get(rsp.getSender()).id;
						for (int i = 0; i < rst.size(); i++) {
							boolean exists = rst.get(i);
							if (exists) {
								if (hashes.get(i) != null)
									this.hashes.get(i).addHashLoc(id);
							}
						}
					}
				}
			}

		} catch (Throwable e) {
			SDFSLogger.getLog().error("error while getting hash", e);
			throw new IOException(e);
		}
	}

	public List<HashLocPair> getHashes() {
		return this.hashes;
	}

	@Override
	public byte getCmdID() {
		return NetworkCMDS.BATCH_HASH_EXISTS_CMD;
	}

}
