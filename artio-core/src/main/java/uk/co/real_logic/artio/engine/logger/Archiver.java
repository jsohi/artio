/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine.logger;

import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.logbuffer.RawBlockHandler;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.LangUtil;
import org.agrona.collections.Int2ObjectCache;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.engine.ByteBufferUtil;
import uk.co.real_logic.artio.engine.CompletionPosition;
import uk.co.real_logic.artio.protocol.ReservedValue;
import uk.co.real_logic.artio.protocol.StreamIdentifier;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.function.IntFunction;
import java.util.zip.CRC32;

import static io.aeron.driver.Configuration.TERM_BUFFER_LENGTH_DEFAULT;
import static io.aeron.logbuffer.LogBufferDescriptor.computePosition;
import static io.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;

public class Archiver implements Agent, RawBlockHandler
{
    public interface ArchivedPositionHandler
    {
        void onArchivedPosition(int aeronSessionId, long endPosition, int length);
    }

    private static final int POLL_LENGTH = TERM_BUFFER_LENGTH_DEFAULT;

    private static final long UNKNOWN_POSITION = -1;

    private final IntFunction<SessionArchiver> newSessionArchiver = this::newSessionArchiver;
    private final ArchiveMetaData metaData;
    private final Int2ObjectCache<SessionArchiver> sessionIdToArchive;
    private final StreamIdentifier streamId;
    private final String agentNamePrefix;
    private final CompletionPosition completionPosition;
    private final LogDirectoryDescriptor directoryDescriptor;
    private final CRC32 checksum = new CRC32();
    private final DataHeaderFlyweight header = new DataHeaderFlyweight();

    private ArchivedPositionHandler positionHandler = (aeronSessionId, endPosition, length) -> {};

    private boolean isClosed = false;
    private Subscription subscription;

    public Archiver(
        final ArchiveMetaData metaData,
        final int cacheNumSets,
        final int cacheSetSize,
        final StreamIdentifier streamId,
        final String agentNamePrefix,
        final CompletionPosition completionPosition)
    {
        this.metaData = metaData;
        this.directoryDescriptor = metaData.directoryDescriptor();
        this.streamId = streamId;
        this.agentNamePrefix = agentNamePrefix;
        this.completionPosition = completionPosition;
        sessionIdToArchive = new Int2ObjectCache<>(cacheNumSets, cacheSetSize, SessionArchiver::close);
    }

    public Archiver subscription(final Subscription subscription)
    {
        // Clear to ensure not holding references to old subscription objects
        sessionIdToArchive.clear();
        this.subscription = subscription;
        return this;
    }

    public int doWork()
    {
        if (subscription == null)
        {
            return 0;
        }

        return (int)subscription.rawPoll(this, POLL_LENGTH);
    }

    private SessionArchiver newSessionArchiver(final int sessionId)
    {
        final Image image = subscription.imageBySessionId(sessionId);
        if (image == null)
        {
            return null;
        }

        final int initialTermId = image.initialTermId();
        final int termBufferLength = image.termBufferLength();
        metaData.write(streamId, sessionId, initialTermId, termBufferLength);
        return new SessionArchiver(sessionId, image);
    }

    public String roleName()
    {
        return agentNamePrefix + "Archiver";
    }

    public void onBlock(
        final FileChannel fileChannel,
        final long fileOffset,
        final UnsafeBuffer termBuffer,
        final int termOffset,
        final int length,
        final int aeronSessionId,
        final int termId)
    {
        session(aeronSessionId).onBlock(
            fileChannel, fileOffset, termBuffer, termOffset, length, aeronSessionId, termId);
    }

    public long positionOf(final int aeronSessionId)
    {
        final SessionArchiver archive = session(aeronSessionId);

        if (archive == null)
        {
            return UNKNOWN_POSITION;
        }

        return archive.archivedPosition();
    }

    public boolean patch(
        final int aeronSessionId,
        final DirectBuffer bodyBuffer,
        final int bodyOffset,
        final int bodyLength)
    {
        return session(aeronSessionId).patch(bodyBuffer, bodyOffset, bodyLength);
    }

    public SessionArchiver session(final int sessionId)
    {
        return sessionIdToArchive.computeIfAbsent(sessionId, newSessionArchiver);
    }

    public void onClose()
    {
        if (!isClosed)
        {
            quiesce();

            sessionIdToArchive.clear();
            metaData.close();
            CloseHelper.close(subscription);

            isClosed = true;
        }
    }

    private void quiesce()
    {
        while (!completionPosition.hasCompleted())
        {
            Thread.yield();
        }

        if (subscription == null)
        {
            return;
        }

        final Long2LongHashMap completedPositions = completionPosition.positions();
        if (!completionPosition.wasStartupComplete())
        {
            subscription.forEachImage(
                (image) ->
                {
                    final int aeronSessionId = image.sessionId();
                    final long currentPosition = image.position();
                    final long completedPosition = completedPositions.get(aeronSessionId);
                    int toPoll = (int)(completedPosition - currentPosition);
                    while (toPoll > 0 && !image.isClosed())
                    {
                        toPoll -= image.rawPoll(this, toPoll);

                        Thread.yield();
                    }
                });
        }
    }

    public class SessionArchiver implements AutoCloseable, RawBlockHandler
    {
        public static final int UNKNOWN = -1;
        private final int sessionId;
        private final Image image;
        private final int termBufferLength;
        private final int positionBitsToShift;
        private final int initialTermId;

        private int currentTermId = UNKNOWN;
        private RandomAccessFile currentLogFile;
        private FileChannel currentLogChannel;

        protected SessionArchiver(final int sessionId, final Image image)
        {
            this.sessionId = sessionId;
            this.image = image;
            termBufferLength = image.termBufferLength();
            positionBitsToShift = Integer.numberOfTrailingZeros(termBufferLength);
            initialTermId = image.initialTermId();
        }

        public int poll()
        {
            return image.rawPoll(this, POLL_LENGTH);
        }

        public void onBlock(
            final FileChannel fileChannel,
            final long fileOffset,
            final UnsafeBuffer termBuffer,
            final int termOffset,
            final int length,
            final int sessionId,
            final int termId)
        {
            try
            {
                if (termId != currentTermId)
                {
                    close();
                    final File location = logFile(termId);
                    currentLogFile = openFile(location);
                    currentLogChannel = currentLogFile.getChannel();
                    currentTermId = termId;
                }

                writeChecksumForBlock(termBuffer, termOffset, length);

                final long transferred = fileChannel.transferTo(fileOffset, length, currentLogChannel);
                final long endPosition = computePosition(
                    termId, termOffset + length, positionBitsToShift, initialTermId);
                positionHandler.onArchivedPosition(sessionId, endPosition, length);

                if (transferred != length)
                {
                    final File location = logFile(termId);
                    throw new IllegalStateException(String.format(
                        "Failed to transfer %d bytes to %s, only transferred %d bytes",
                        length,
                        location,
                        transferred));
                }
            }
            catch (final IOException ex)
            {
                LangUtil.rethrowUnchecked(ex);
            }
        }

        private void writeChecksumForBlock(final UnsafeBuffer termBuffer, final int termOffset, final int length)
        {
            final ByteBuffer byteBuffer = termBuffer.byteBuffer();
            final int end = termOffset + length - HEADER_LENGTH;
            int remaining = length;
            int offset = termOffset;

            while (offset < end)
            {
                header.wrap(termBuffer, offset, remaining);
                final int frameLength = header.frameLength();
                final int messageOffset = offset + HEADER_LENGTH;
                checksum.reset();

                if (byteBuffer != null)
                {
                    final int limit = offset + frameLength;
                    if (messageOffset > limit)
                    {
                        throw new IllegalArgumentException(
                            String.format("%d is > than %d or < 0", messageOffset, limit));
                    }
                    ByteBufferUtil.limit(byteBuffer, limit);
                    ByteBufferUtil.position(byteBuffer, messageOffset);

                    checksum.update(byteBuffer);
                }
                else
                {
                    final int messageLength = frameLength - HEADER_LENGTH;
                    final byte[] bytes = termBuffer.byteArray();

                    checksum.update(bytes, messageOffset, messageLength);
                }

                writeChecksum(header);

                final int alignedFrameLength = ArchiveDescriptor.alignTerm(frameLength);
                offset += alignedFrameLength;
                remaining -= alignedFrameLength;
            }
        }

        public long archivedPosition()
        {
            return image.position();
        }

        public boolean patch(
            final DirectBuffer bodyBuffer, final int readOffset, final int bodyLength)
        {
            if (bodyBuffer.capacity() < readOffset + bodyLength ||
                bodyLength < DataHeaderFlyweight.HEADER_LENGTH)
            {
                return false;
            }

            header.wrap(bodyBuffer, readOffset, bodyLength);
            final int termId = header.termId();
            final int termWriteOffset = header.termOffset();
            final long position = computePosition(termId, termWriteOffset, positionBitsToShift, image.initialTermId());

            if (position + bodyLength >= archivedPosition())
            {
                // Can only patch historic files
                return false;
            }

            try
            {
                checkOverflow(bodyLength, termWriteOffset);

                // Find the files to patch
                final RandomAccessFile patchTermLogFile;
                final FileChannel patchTermLogChannel;
                if (termId == currentTermId)
                {
                    patchTermLogChannel = currentLogChannel;
                    patchTermLogFile = currentLogFile;
                }
                else
                {
                    // if file doesn't exist it gets created here
                    final File file = logFile(termId);
                    patchTermLogFile = openFile(file);
                    patchTermLogChannel = patchTermLogFile.getChannel();
                }

                writeToFile(
                    bodyBuffer, readOffset, bodyLength, termWriteOffset, patchTermLogChannel, patchTermLogFile);

                close(patchTermLogChannel);

                return true;
            }
            catch (final IOException ex)
            {
                LangUtil.rethrowUnchecked(ex);
                return false;
            }
        }

        public void close()
        {
            CloseHelper.close(currentLogChannel);
        }

        private RandomAccessFile openFile(final File location) throws IOException
        {
            final RandomAccessFile file = new RandomAccessFile(location, "rwd");
            file.setLength(termBufferLength);
            return file;
        }

        private File logFile(final int termId)
        {
            return directoryDescriptor.logFile(streamId, sessionId, termId);
        }

        private void checkOverflow(final int bodyLength, final int termOffset)
        {
            if (termOffset + bodyLength > termBufferLength)
            {
                throw new IllegalArgumentException("Unable to write patch beyond the length of the log buffer");
            }
        }

        @SuppressWarnings("FinalParameters")
        private void writeToFile(
            final DirectBuffer bodyBuffer,
            int readOffset,
            final int bodyLength,
            int termWriteOffset,
            final FileChannel patchTermLogChannel,
            final RandomAccessFile patchTermLogFile) throws IOException
        {
            checksum.reset();

            final int messageOffset = readOffset + HEADER_LENGTH;
            final ByteBuffer byteBuffer = bodyBuffer.byteBuffer();
            if (byteBuffer != null)
            {
                // Update Checksum
                final int limit = readOffset + bodyLength;
                ByteBufferUtil.limit(byteBuffer, limit);
                ByteBufferUtil.position(byteBuffer, messageOffset);
                checksum.update(byteBuffer);
                writeChecksum(header);

                // Write patch
                ByteBufferUtil.limit(byteBuffer, limit);
                ByteBufferUtil.position(byteBuffer, readOffset);
                while (byteBuffer.remaining() > 0)
                {
                    termWriteOffset += patchTermLogChannel.write(byteBuffer, termWriteOffset);
                }
            }
            else
            {
                // Update Checksum
                final byte[] bytes = bodyBuffer.byteArray();
                checksum.update(bytes, messageOffset, bodyLength - HEADER_LENGTH);

                writeChecksum(header);

                // Write patch
                patchTermLogFile.seek(termWriteOffset);
                patchTermLogFile.write(bytes, readOffset, bodyLength);
            }
        }

        private void writeChecksum(final DataHeaderFlyweight header)
        {
            final int checksumValue = (int)checksum.getValue();
            final int clusterStreamId = ReservedValue.clusterStreamId(header.reservedValue());
            header.reservedValue(ReservedValue.of(clusterStreamId, checksumValue));
        }

        private void close(final FileChannel patchTermLogChannel) throws IOException
        {
            if (patchTermLogChannel != currentLogChannel)
            {
                patchTermLogChannel.close();
            }
        }
    }
}
