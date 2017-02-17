/*
 *
 *  Copyright 2015 Flipkart Internet Pvt. Ltd.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.flipkart.fdp.migration.distcp.codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CryptoCodec;
import org.apache.hadoop.crypto.CryptoInputStream;
import org.apache.hadoop.crypto.CryptoOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.security.authentication.client.AuthenticationException;

import com.flipkart.fdp.migration.distcp.config.ConnectionConfig;
import com.flipkart.fdp.migration.distcp.core.MirrorUtils;
import com.flipkart.fdp.migration.vo.FileTuple;

public class GenericHadoopCodec implements DCMCodec {

	private static final String _SEPARATOR = ",";

	private FileSystem fs = null;

	private Configuration conf = null;

	public GenericHadoopCodec(Configuration conf, ConnectionConfig config,
			FileSystem fs) throws Exception {
		this.fs = fs;
		this.conf = conf;
	}

	public OutputStream createOutputStream(String path, boolean useCompression,
			String codecName, boolean append,boolean encrypt,byte[] encryptKey,byte[] encryptIV) throws IOException {

		OutputStream out = null;
		if (append)
			out = fs.append(new Path(path));
		else
			out = fs.create(new Path(path));

		if (encrypt) {
			CryptoCodec c = CryptoCodec.getInstance(conf);
			out = new CryptoOutputStream(out,c,8192,encryptKey,encryptIV);
		}
		//TODO support to be added for 4mc and other supported compression formats
		if (useCompression)
			out = MirrorUtils.getCodecOutputStream(conf, codecName, out);

		return out;
	}

	public InputStream createInputStream(String path, boolean useDeCompression,boolean decrypt, byte[] decryptKey, byte[] decryptIV)
			throws IOException {

		InputStream in = fs.open(new Path(path));

		if (decrypt) {
			System.out.println("Creating decryption stream");
			CryptoCodec c = CryptoCodec.getInstance(conf);
			in = new CryptoInputStream(in,c,8192,decryptKey,decryptIV);
		}

		if (useDeCompression)
			in = MirrorUtils.getCodecInputStream(conf, path, in);

		return in;

	}

	public boolean deleteSoureFile(String path) throws IOException {
		return fs.delete(new Path(path), false);
	}

	public boolean isSplitable() {

		return false;
	}

	public List<FileTuple> getInputPaths(String path,
			Collection<String> excludeList) throws Exception {

		return getInputPaths(Arrays.asList(path.split(_SEPARATOR)), excludeList);
	}

	public List<FileTuple> getInputPaths(Collection<String> paths,
			Collection<String> excludeList) throws Exception {

		System.out.println("A total of " + paths.size() + " paths to scan...");

		List<FileTuple> fileList = new ArrayList<FileTuple>();
		List<String> inputPaths = new ArrayList<String>();

		// Process regular expression based paths
		for (String path : paths) {

			System.out.println("Processing path: " + path);
			FileStatus[] stats = fs.globStatus(new Path(path));
			if (stats == null || stats.length <= 0)
				continue;

			for (FileStatus fstat : stats) {
				if (fstat.isFile()) {
					fileList.add(new FileTuple(MirrorUtils.getSimplePath(fstat
							.getPath()), fstat.getLen(), fstat
							.getModificationTime()));
				} else {
					inputPaths.add(MirrorUtils.getSimplePath(fstat.getPath()));
				}
			}
		}

		if (inputPaths.size() > 0) {

			for (String path : inputPaths) {

				List<FileTuple> fstat = getFileStatusRecursive(new Path(path),
						excludeList);
				fileList.addAll(fstat);
			}
		}
		return fileList;
	}

	public List<FileTuple> getFileStatusRecursive(Path path,
			Collection<String> excludeList) throws IOException,
			AuthenticationException {

		List<FileTuple> response = new ArrayList<FileTuple>();

		FileStatus file = fs.getFileStatus(path);
		//TODO excludeList to be checked if file (not folder) is mentioned in excludeList.
		if (file != null && file.isFile()) {
			response.add(new FileTuple(
					MirrorUtils.getSimplePath(file.getPath()), file.getLen(),
					file.getModificationTime()));
			return response;
		}

		FileStatus[] fstats = fs.listStatus(path);

		if (fstats != null && fstats.length > 0) {

			for (FileStatus fstat : fstats) {

				if (fstat.isDirectory()
						&& !excludeList.contains(MirrorUtils
								.getSimplePath(fstat.getPath()))) {

					response.addAll(getFileStatusRecursive(fstat.getPath(),
							excludeList));
				} else {
					
					//TODO excludeList to be checked if file (not folder) is mentioned in excludeList.

					response.add(new FileTuple(MirrorUtils.getSimplePath(fstat
							.getPath()), fstat.getLen(), fstat
							.getModificationTime()));
				}
			}
		}
		return response;
	}

	@Override
	public boolean isExistsPath(String path) throws IOException {
		return fs.exists(new Path(path));
	}

	public void close() throws IOException {
		IOUtils.closeStream(fs);

	}

	@Override
	public void setConf(Configuration conf) {
		this.conf = conf;

	}

	@Override
	public Configuration getConf() {
		return conf;
	}

	@Override
	public boolean renameFile(String srcPath, String destPath)
			throws IOException {
		Path destFSPath = new Path(destPath);
		if (fs.exists(destFSPath))
			fs.delete(destFSPath, true);
		return fs.rename(new Path(srcPath), destFSPath);
	}

}
