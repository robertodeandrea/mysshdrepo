/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.mina.transport.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

import org.apache.mina.api.RuntimeIoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class NioSelectorLoop implements SelectorLoop {

    private final Logger logger;

    /**
     * A timeout used for the select, as we need to get out to deal with idle sessions
     */
    private static final long SELECT_TIMEOUT = 1000L;

    /** the selector managed by this class */
    private Selector selector;

    /** the worker thread in charge of polling the selector */
    private final SelectorWorker worker;

    /** the number of service using this selector */
    private int serviceCount = 0;

    /** Read buffer for all the incoming bytes (default to 64Kb) */
    private final ByteBuffer readBuffer = ByteBuffer.allocate(64 * 1024);

    public NioSelectorLoop(final String prefix, final int index) {
        logger = LoggerFactory.getLogger(NioSelectorLoop.class.getName() + ":" + prefix + "-" + index);
        worker = new SelectorWorker(prefix, index);

        try {
            selector = Selector.open();
        } catch (final IOException ioe) {
            logger.error("Impossible to open a new NIO selector, O/S is out of file descriptor ?");
            throw new RuntimeIoException(ioe);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(final boolean accept, final boolean read, final boolean write,
            final SelectorListener listener, final SelectableChannel channel) {
        logger.debug("registering : {} for accept : {}, read : {}, write : {}", new Object[] { listener, accept, read,
                                write });
        int ops = 0;
        if (accept) {
            ops |= SelectionKey.OP_ACCEPT;
        }
        if (read) {
            ops |= SelectionKey.OP_READ;
        }
        if (write) {
            ops |= SelectionKey.OP_WRITE;
        }
        try {
            channel.register(selector, ops, listener);
        } catch (final ClosedChannelException e) {
            logger.error("Trying to register an already closed channel : ", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void modifyRegistration(final boolean accept, final boolean read, final boolean write,
            final SelectorListener listener, final SelectableChannel channel) {
        logger.debug("modifying registration : {} for accept : {}, read : {}, write : {}", new Object[] { listener,
                                accept, read, write });

        final SelectionKey key = channel.keyFor(selector);
        if (key == null) {
            logger.error("Trying to modify the registration of a not registered channel");
            return;
        }

        int ops = 0;
        if (accept) {
            ops |= SelectionKey.OP_ACCEPT;
        }
        if (read) {
            ops |= SelectionKey.OP_READ;
        }
        if (write) {
            ops |= SelectionKey.OP_WRITE;
        }
        key.interestOps(ops);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregister(final SelectorListener listener, final SelectableChannel channel) {
        logger.debug("unregistering : {}", listener);
        final SelectionKey key = channel.keyFor(selector);
        if (key == null) {
            logger.error("Trying to modify the registration of a not registered channel");
            return;
        }
        key.cancel();
        key.attach(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void incrementServiceCount() {
        serviceCount++;
        logger.debug("service count: {}", serviceCount);
        if (serviceCount == 1) {
            worker.start();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void decrementServiceCount() {
        serviceCount--;
        logger.debug("service count: {}", serviceCount);
        if (serviceCount < 0) {
            logger.error("service count should not be negative : bug ?");
        }
    }

    /**
     * The worker processing incoming session creation, session destruction requests, session write and reads. It will
     * also bind new servers.
     */
    private class SelectorWorker extends Thread {

        public SelectorWorker(final String prefix, final int index) {
            super("SelectorWorker " + prefix + "-" + index);
        }

        @Override
        public void run() {
            if (selector == null) {
                logger.debug("opening a new selector");

                try {
                    selector = Selector.open();
                } catch (final IOException e) {
                    logger.error("IOException while opening a new Selector", e);
                }
            }

            for (;;) {
                try {
                    logger.debug("selecting...");
                    final int readyCount = selector.select();
                    logger.debug("... done selecting : {} events", readyCount);
                    if (readyCount > 0) {
                        final Iterator<SelectionKey> it = selector.selectedKeys().iterator();

                        while (it.hasNext()) {
                            final SelectionKey key = it.next();
                            final SelectorListener listener = (SelectorListener) key.attachment();
                            listener.ready(key.isAcceptable(), key.isReadable(), key.isReadable() ? readBuffer : null,
                                    key.isWritable());
                            it.remove();
                        }
                    }

                } catch (final Exception e) {
                    logger.error("Unexpected exception : ", e);
                }

                // stop the worker if needed (no more service)
                synchronized (NioSelectorLoop.this) {
                    logger.debug("remaing {} service", serviceCount);
                    if (serviceCount <= 0) {
                        logger.debug("stop the worker");
                        break;
                    }
                }
            }
        }
    }
}