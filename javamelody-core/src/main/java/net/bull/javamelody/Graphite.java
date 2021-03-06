/*
 * Copyright 2008-2017 by Emeric Vernat
 *
 *     This file is part of Java Melody.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.bull.javamelody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import javax.net.SocketFactory;

/**
 * Publish chart data to Graphite, http://graphiteapp.org/.
 * @author Emeric Vernat
 */
class Graphite {
	private static final int DEFAULT_GRAPHITE_PORT = 2003;
	private static final char SEPARATOR = ' ';

	private final SocketFactory socketFactory;
	private final InetAddress address;
	private final int port;
	private final String prefix;

	private final DecimalFormat decimalFormat = new DecimalFormat("0.00",
			DecimalFormatSymbols.getInstance(Locale.US));
	private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	private final Writer bufferWriter;
	private long lastTime;
	private String lastTimestamp;

	Graphite(SocketFactory socketFactory, InetAddress address, int port, Charset charset,
			String prefix) {
		super();
		assert socketFactory != null;
		assert address != null;
		assert charset != null;
		assert prefix != null;
		this.socketFactory = socketFactory;
		this.address = address;
		this.port = port;
		this.prefix = prefix;
		this.bufferWriter = new OutputStreamWriter(buffer, charset);
	}

	static Graphite getInstance(String application) {
		final String graphiteAddress = Parameters.getParameter(Parameter.GRAPHITE_ADDRESS);
		if (graphiteAddress != null) {
			assert application != null;
			final String address;
			final int port;
			final int index = graphiteAddress.indexOf(':');
			if (index != -1 && index < graphiteAddress.length()) {
				address = graphiteAddress.substring(0, index);
				port = Integer
						.parseInt(graphiteAddress.substring(index + 1, graphiteAddress.length()));
			} else {
				address = graphiteAddress;
				port = DEFAULT_GRAPHITE_PORT;
			}
			// application est du genre "/testapp_www.host.com"
			final String prefix = "javamelody." + application.replace("/", "").replace('.', '-')
					.replace('_', '.').replace('-', '_').replace(SEPARATOR, '_') + '.';
			try {
				return new Graphite(SocketFactory.getDefault(), InetAddress.getByName(address),
						port, Charset.forName("UTF-8"), prefix);
			} catch (final UnknownHostException e) {
				throw new IllegalArgumentException("Invalid host: " + address, e);
			}
		}
		return null;
	}

	synchronized void addValue(String metric, double value) throws IOException {
		final long timeInSeconds = System.currentTimeMillis() / 1000;
		if (lastTime != timeInSeconds) {
			lastTimestamp = String.valueOf(timeInSeconds);
			lastTime = timeInSeconds;
		}
		bufferWriter.append(prefix).append(metric).append(' ');
		bufferWriter.append(decimalFormat.format(value)).append(' ');
		bufferWriter.append(lastTimestamp).append('\n');
	}

	synchronized void send() throws IOException {
		try {
			bufferWriter.flush();
			final Socket socket = createSocket();
			try {
				buffer.writeTo(socket.getOutputStream());
			} finally {
				socket.close();
			}
		} catch (final ConnectException e) {
			throw new IOException("Error connecting to Graphite at " + address + ':' + port, e);
		} finally {
			// finally to be sure to not keep too much data in buffer
			// including when the socket can't connect
			buffer.reset();
		}
	}

	private Socket createSocket() throws IOException {
		return socketFactory.createSocket(address, port);
	}
}
