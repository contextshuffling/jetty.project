//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AutoFragmentTest
{
    private static final long MAX_FRAME_SIZE = 10;

    private WebSocketServer server;
    private TestFrameHandler serverHandler;
    private URI serverUri;

    private WebSocketCoreClient client;

    @BeforeEach
    public void setup() throws Exception
    {
        serverHandler = new TestFrameHandler()
        {
            @Override
            public void onOpen(CoreSession coreSession)
            {
                coreSession.setMaxFrameSize(MAX_FRAME_SIZE);
                coreSession.setAutoFragment(true);
                super.onOpen(coreSession);
            }
        };

        server = new WebSocketServer(serverHandler);
        server.start();
        serverUri = new URI("ws://localhost:" + server.getLocalPort());

        client = new WebSocketCoreClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testAutoFragmentToMaxFrameSize() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();
        CompletableFuture<FrameHandler.CoreSession> connect = client.connect(clientHandler, serverUri);
        connect.get(5, TimeUnit.SECONDS);

        // Verify the correct max frame size was set.
        assertTrue(serverHandler.open.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.coreSession.getMaxFrameSize(), is(MAX_FRAME_SIZE));
        assertThat(serverHandler.coreSession.isAutoFragment(), is(true));

        // Send a message which is too large.
        int size = (int)MAX_FRAME_SIZE * 2;
        byte[] message = new byte[size];
        Arrays.fill(message, 0, size, (byte)'X');
        clientHandler.coreSession.sendFrame(new Frame(OpCode.BINARY, BufferUtil.toBuffer(message)), Callback.NOOP, false);

        // We should not receive any frames larger than the max frame size.
        // So our message should be split into two frames.
        Frame frame = serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame);
        assertThat(frame.getOpCode(), is(OpCode.BINARY));
        assertThat(frame.getPayloadLength(), is(MAX_FRAME_SIZE));
        assertThat(frame.isFin(), is(false));

        // Second frame should be final and contain rest of the data.
        frame = serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame);
        assertThat(frame.getOpCode(), is(OpCode.CONTINUATION));
        assertThat(frame.getPayloadLength(), is(MAX_FRAME_SIZE));
        assertThat(frame.isFin(), is(true));

        clientHandler.sendClose();
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
    }
}
