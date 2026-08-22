package io.github.gitsilence.agent.tools.builtin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class BoundedProcessOutput implements Runnable {

    private final InputStream input;
    private final int maxBytes;
    private final int headLimit;
    private final byte[] tail;
    private final ByteArrayOutputStream head;
    private final Path spillPath;
    private OutputStream spill;
    private int tailStart;
    private int tailSize;
    private long totalBytes;
    private IOException error;
    private volatile boolean closedByOwner;

    BoundedProcessOutput(InputStream input,
                         int maxBytes,
                         Path spillDirectory,
                         String prefix) {
        this.input = input;
        this.maxBytes = maxBytes;
        this.headLimit = (maxBytes + 1) / 2;
        this.tail = new byte[maxBytes / 2];
        this.head = new ByteArrayOutputStream(headLimit);
        Path candidate = null;
        if (spillDirectory != null) {
            try {
                Files.createDirectories(spillDirectory);
                candidate = Files.createTempFile(spillDirectory, prefix, ".log");
                spill = Files.newOutputStream(candidate);
            } catch (IOException unavailable) {
                candidate = null;
                closeQuietly(spill);
                spill = null;
            }
        }
        this.spillPath = candidate;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[8192];
        try {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                totalBytes += read;
                if (spill != null) {
                    spill.write(buffer, 0, read);
                }
                retain(buffer, read);
            }
        } catch (IOException caught) {
            if (!closedByOwner) {
                error = caught;
            }
        } finally {
            closeQuietly(spill);
            closeQuietly(input);
            if (!isTruncated() && spillPath != null) {
                try {
                    Files.deleteIfExists(spillPath);
                } catch (IOException ignored) {
                    // A stale best-effort spill is safer than failing the tool result.
                }
            }
        }
    }

    synchronized String text() {
        byte[] headBytes = head.toByteArray();
        byte[] tailBytes = tailBytes();
        String beginning = new String(headBytes, StandardCharsets.UTF_8);
        String ending = new String(tailBytes, StandardCharsets.UTF_8);
        if (!isTruncated()) {
            return beginning + ending;
        }
        return beginning
            + "\n... " + (totalBytes - headBytes.length - tailBytes.length)
            + " bytes omitted ...\n"
            + ending;
    }

    synchronized boolean isTruncated() {
        return totalBytes > maxBytes;
    }

    synchronized long getTotalBytes() {
        return totalBytes;
    }

    synchronized IOException getError() {
        return error;
    }

    synchronized String retainedSpillPath() {
        return isTruncated() && spillPath != null
            ? spillPath.toAbsolutePath().toString() : null;
    }

    void closeInput() {
        closedByOwner = true;
        closeQuietly(input);
    }

    private synchronized void retain(byte[] bytes, int length) {
        int index = 0;
        int headRemaining = headLimit - head.size();
        if (headRemaining > 0) {
            int copy = Math.min(headRemaining, length);
            head.write(bytes, 0, copy);
            index = copy;
        }
        for (; index < length && tail.length > 0; index++) {
            if (tailSize < tail.length) {
                tail[(tailStart + tailSize) % tail.length] = bytes[index];
                tailSize++;
            } else {
                tail[tailStart] = bytes[index];
                tailStart = (tailStart + 1) % tail.length;
            }
        }
    }

    private byte[] tailBytes() {
        byte[] result = new byte[tailSize];
        for (int i = 0; i < tailSize; i++) {
            result[i] = tail[(tailStart + i) % tail.length];
        }
        return result;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best effort during stream teardown.
        }
    }
}
