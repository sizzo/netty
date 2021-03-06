/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.stream;

import io.netty.buffer.ChannelBuffer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;

import junit.framework.Assert;

import org.junit.Test;

public class ChunkedWriteHandlerTest {
    private static final byte[] BYTES = new byte[1024 * 64];
    private static final File TMP;

    static {
        for (int i = 0; i < BYTES.length; i++) {
            BYTES[i] = (byte) i;
        }

        FileOutputStream out = null;
        try {
            TMP = File.createTempFile("netty-chunk-", ".tmp");
            TMP.deleteOnExit();
            out = new FileOutputStream(TMP);
            out.write(BYTES);
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    // See #310
    @Test
    public void testChunkedStream() {
        check(new ChunkedStream(new ByteArrayInputStream(BYTES)));

        check(new ChunkedStream(new ByteArrayInputStream(BYTES)), new ChunkedStream(new ByteArrayInputStream(BYTES)), new ChunkedStream(new ByteArrayInputStream(BYTES)));

    }

    @Test
    public void testChunkedNioStream() {
        check(new ChunkedNioStream(Channels.newChannel(new ByteArrayInputStream(BYTES))));

        check(new ChunkedNioStream(Channels.newChannel(new ByteArrayInputStream(BYTES))), new ChunkedNioStream(Channels.newChannel(new ByteArrayInputStream(BYTES))), new ChunkedNioStream(Channels.newChannel(new ByteArrayInputStream(BYTES))));

    }


    @Test
    public void testChunkedFile() throws IOException {
        check(new ChunkedFile(TMP));

        check(new ChunkedFile(TMP), new ChunkedFile(TMP), new ChunkedFile(TMP));
    }

    @Test
    public void testChunkedNioFile() throws IOException {
        check(new ChunkedNioFile(TMP));

        check(new ChunkedNioFile(TMP), new ChunkedNioFile(TMP), new ChunkedNioFile(TMP));
    }

    private static void check(ChunkedInput... inputs) {
        EncoderEmbedder<ChannelBuffer> embedder = new EncoderEmbedder<ChannelBuffer>(new ChunkedWriteHandler());
        for (ChunkedInput input: inputs) {
            embedder.offer(input);
        }

        Assert.assertTrue(embedder.finish());

        int i = 0;
        int read = 0;
        for (;;) {
            ChannelBuffer buffer = embedder.poll();
            if (buffer == null) {
                break;
            }
            while (buffer.readable()) {
                Assert.assertEquals(BYTES[i++], buffer.readByte());
                read++;
                if (i == BYTES.length) {
                    i = 0;
                }
            }
        }

        Assert.assertEquals(BYTES.length * inputs.length, read);
    }
}
