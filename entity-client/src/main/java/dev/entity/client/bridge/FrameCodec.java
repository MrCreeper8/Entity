package dev.entity.client.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class FrameCodec {
    private static final Gson GSON = new Gson();

    private FrameCodec() {
    }

    static JsonObject read(DataInputStream input, int maximumBytes) throws IOException {
        int length;
        try {
            length = input.readInt();
        } catch (EOFException eof) {
            throw eof;
        }
        if (length < 1 || length > maximumBytes) {
            throw new IOException("Invalid EntityBridge frame length: " + length);
        }
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("EntityBridge frame ended early");
        }
        try {
            var parsed = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
            if (parsed == null) throw new IOException("EntityBridge frame was null");
            return parsed;
        } catch (JsonParseException exception) {
            throw new IOException("Invalid EntityBridge JSON", exception);
        }
    }

    static void write(DataOutputStream output, JsonObject frame, int maximumBytes) throws IOException {
        byte[] payload = GSON.toJson(frame).getBytes(StandardCharsets.UTF_8);
        if (payload.length < 1 || payload.length > maximumBytes) {
            throw new IOException("Outbound EntityBridge frame is too large: " + payload.length);
        }
        output.writeInt(payload.length);
        output.write(payload);
        output.flush();
    }
}
