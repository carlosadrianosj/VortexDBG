package com.github.unidbg.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Merges keyboard stdin and piped MCP command input.
 * Reads from whichever stream has available data, prioritizing piped commands.
 */
public class MergedInputStream extends InputStream {

    private final InputStream keyboard;
    private final InputStream pipe;
    private volatile boolean pipeClosed;

    public MergedInputStream(InputStream keyboard, InputStream pipe) {
        this.keyboard = keyboard;
        this.pipe = pipe;
    }

    private int pipeAvailable() {
        if (pipeClosed) {
            return 0;
        }
        try {
            return pipe.available();
        } catch (IOException e) {
            pipeClosed = true;
            return 0;
        }
    }

    private int keyboardAvailable() {
        try {
            return keyboard.available();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public int read() throws IOException {
        while (true) {
            if (pipeAvailable() > 0) {
                return pipe.read();
            }
            if (keyboardAvailable() > 0) {
                return keyboard.read();
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        while (true) {
            int pipeAvail = pipeAvailable();
            if (pipeAvail > 0) {
                return pipe.read(b, off, Math.min(len, pipeAvail));
            }
            int kbAvail = keyboardAvailable();
            if (kbAvail > 0) {
                return keyboard.read(b, off, Math.min(len, kbAvail));
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    @Override
    public int available() throws IOException {
        return pipeAvailable() + keyboardAvailable();
    }

    @Override
    public void close() throws IOException {
        pipeClosed = true;
        pipe.close();
        keyboard.close();
    }
}
