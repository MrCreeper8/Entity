package dev.entity.client.autonomy.policy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.CRC32;

/** Strict restart-safe encoding for one exact root-owned Home transfer intent. */
public final class HomeTransferIntentCodec {
    private static final int MAGIC = 0x45324854; // E2HT
    private static final int SCHEMA = 1;
    private static final int MAXIMUM_BYTES = 65_536;

    private HomeTransferIntentCodec() {
    }

    public static String encode(HomeEconomySession.PendingTransfer transfer) {
        Objects.requireNonNull(transfer, "transfer");
        if (transfer.containerIdentity() == null) {
            throw new IllegalArgumentException(
                    "root-owned Home transfer requires exact container identity");
        }
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
                output.writeInt(MAGIC);
                output.writeInt(SCHEMA);
                output.writeUTF(transfer.id());
                output.writeUTF(transfer.direction().name());
                output.writeUTF(transfer.categoryId());
                output.writeUTF(transfer.item());
                output.writeInt(transfer.count());
                output.writeInt(transfer.syncId());
                output.writeInt(transfer.sourceSlot());
                output.writeInt(transfer.destinationSlot());
                output.writeInt(transfer.destinationCountBefore());
                output.writeInt(transfer.playerCountBefore());
                output.writeInt(transfer.chestCountBefore());
                output.writeLong(transfer.startedAtMillis());
                writeIdentity(output, transfer.containerIdentity());
            }
            byte[] payload = payloadBytes.toByteArray();
            if (payload.length > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("Home transfer intent exceeds maximum size");
            }
            CRC32 crc = new CRC32();
            crc.update(payload);
            ByteArrayOutputStream envelope = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(envelope)) {
                output.writeInt(payload.length);
                output.write(payload);
                output.writeLong(crc.getValue());
            }
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(envelope.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory Home transfer encoding failed", impossible);
        }
    }

    public static HomeEconomySession.PendingTransfer decode(String encoded) {
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(
                    requireText(encoded, "encoded transfer"));
            if (envelope.length > MAXIMUM_BYTES + 12) {
                throw new IllegalArgumentException("Home transfer intent exceeds maximum size");
            }
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(envelope))) {
                int length = input.readInt();
                if (length < 0 || length != envelope.length - 12) {
                    throw new IllegalArgumentException(
                            "invalid Home transfer payload length");
                }
                byte[] payload = input.readNBytes(length);
                long checksum = input.readLong();
                if (input.read() != -1) {
                    throw new IllegalArgumentException("trailing Home transfer data");
                }
                CRC32 crc = new CRC32();
                crc.update(payload);
                if (crc.getValue() != checksum) {
                    throw new IllegalArgumentException("Home transfer checksum mismatch");
                }
                return decodePayload(payload);
            }
        } catch (EOFException error) {
            throw new IllegalArgumentException("truncated Home transfer intent", error);
        } catch (IOException | IllegalArgumentException error) {
            if (error instanceof IllegalArgumentException invalid) throw invalid;
            throw new IllegalArgumentException("invalid Home transfer intent", error);
        }
    }

    private static HomeEconomySession.PendingTransfer decodePayload(byte[] payload)
            throws IOException {
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA) {
                throw new IllegalArgumentException("unsupported Home transfer schema");
            }
            HomeEconomySession.PendingTransfer transfer =
                    new HomeEconomySession.PendingTransfer(
                            input.readUTF(),
                            HomeStockPolicy.Direction.valueOf(input.readUTF()),
                            input.readUTF(),
                            input.readUTF(),
                            input.readInt(), input.readInt(), input.readInt(), input.readInt(),
                            input.readInt(), input.readInt(), input.readInt(), input.readLong(),
                            readIdentity(input));
            if (input.read() != -1) {
                throw new IllegalArgumentException("trailing Home transfer payload");
            }
            return transfer;
        }
    }

    private static void writeIdentity(
            DataOutputStream output,
            HomeEconomySession.ContainerIdentity identity) throws IOException {
        output.writeUTF(identity.storageId());
        output.writeInt(identity.halves().size());
        for (HomeEconomySession.ChestHalf half : identity.halves()) {
            output.writeUTF(half.position().dimension());
            output.writeInt(half.position().x());
            output.writeInt(half.position().y());
            output.writeInt(half.position().z());
            output.writeUTF(half.blockId());
            output.writeUTF(half.facing());
            output.writeUTF(half.chestType());
        }
    }

    private static HomeEconomySession.ContainerIdentity readIdentity(
            DataInputStream input) throws IOException {
        String storageId = input.readUTF();
        int count = input.readInt();
        if (count < 1 || count > 2) {
            throw new IllegalArgumentException("invalid Home transfer container half count");
        }
        ArrayList<HomeEconomySession.ChestHalf> halves = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            halves.add(new HomeEconomySession.ChestHalf(
                    new FieldKitLedger.Position(
                            input.readUTF(), input.readInt(), input.readInt(), input.readInt()),
                    input.readUTF(), input.readUTF(), input.readUTF()));
        }
        return new HomeEconomySession.ContainerIdentity(storageId, halves);
    }

    private static String requireText(String value, String label) {
        String result = Objects.requireNonNullElse(value, "").trim();
        if (result.isEmpty() || result.length() > MAXIMUM_BYTES * 2) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return result;
    }
}
