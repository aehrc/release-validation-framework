package org.ihtsdo.rvf.core.service.structure.resource;

import java.io.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

public class TextFileResourceProvider implements ResourceProvider {

	private final File file;
	private final List<String> fileNames;

	public TextFileResourceProvider(File file, String fileName) {
		this.file = file;
		fileNames = new ArrayList<>();
		fileNames.add(fileName);
	}

	@Override
	public BufferedReader getReader(String name, Charset charset) throws IOException {
		return new BufferedReader(new InputStreamReader(new FileInputStream(file), charset));
	}

	@Override
	public String getFilePath() {
		return file.getAbsolutePath();
	}

	@Override
	public List<String> getFileNames() {
		return fileNames;
	}

	@Override
	public boolean match(String name) {
		return false;
	}

}
