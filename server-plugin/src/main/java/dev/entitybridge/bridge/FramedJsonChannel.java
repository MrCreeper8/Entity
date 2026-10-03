package dev.entitybridge.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** A four-byte unsigned length followed by one strict UTF-8 JSON object. */
public final class FramedJsonChannel implements Closeable {
    private final DataInputStream input;
    private final DataOutputStream output;
    private final int maxFrameBytes;

    public FramedJsonChannel(InputStream input, OutputStream output, int maxFrameBytes) {
        this.input = new DataInputStream(input);
        this.output = new DataOutputStream(output);
        this.maxFrameBytes = maxFrameBytes;
    }

    public JsonObject read() throws IOException {
        long length = Integer.toUnsignedLong(input.readInt());
        if (length == 0 || length > maxFrameBytes) {
            throw new ProtocolException("Invalid frame length: " + length);
        }
        byte[] bytes = new byte[(int) length];
        input.readFully(bytes);

        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException("Frame is not valid UTF-8", exception);
        }

        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw new ProtocolException("JSON frame must be an object");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new ProtocolException("Frame contains invalid JSON", exception);
        }
    }

    public synchronized void write(JsonObject frame) throws IOException {
        byte[] bytes = frame.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maxFrameBytes) {
            throw new ProtocolException("Outgoing frame length is invalid: " + bytes.length);
        }
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            input.close();
        } finally {
            output.close();
        }
    }
}
