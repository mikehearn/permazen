
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.core;

import com.google.common.base.Preconditions;

import java.io.Serializable;
import java.security.SecureRandom;
import java.util.regex.Pattern;

import net.jcip.annotations.Immutable;

import org.jsimpledb.kv.KeyRange;
import org.jsimpledb.util.ByteReader;
import org.jsimpledb.util.ByteUtil;
import org.jsimpledb.util.ByteWriter;
import org.jsimpledb.util.UnsignedIntEncoder;

/**
 * Object IDs. Instances identify individual {@link Database} objects.
 */
@Immutable
public class ObjId implements Comparable<ObjId>, Serializable {

    /**
     * The number of bytes in the binary encoding of an {@link ObjId}.
     */
    public static final int NUM_BYTES = 8;

    /**
     * Regular expression that matches the string encoding of an {@link ObjId}.
     */
    public static final Pattern PATTERN = Pattern.compile("\\p{XDigit}{" + (NUM_BYTES * 2) + "}");

    private static final long serialVersionUID = 1598203254073015116L;

    private static final ThreadLocal<SecureRandom> RANDOM = new ThreadLocal<SecureRandom>() {
        @Override
        protected SecureRandom initialValue() {
            return new SecureRandom();
        }
    };

    private final long value;

// Constructors

    /**
     * Create a new, random instance with the given storage ID.
     *
     * <p>
     * The created instance will never equal the {@linkplain #getSentinel sentinel value} for {@code storageId}.
     *
     * @param storageId storage ID, must be greater than zero
     * @throws IllegalArgumentException if {@code storageId} is zero or negative
     */
    public ObjId(int storageId) {
        this(ObjId.buildRandom(storageId));
    }

    /**
     * Constructor that parses a string previously returned by {@link #toString}.
     *
     * @param string string encoding of an instance
     * @throws IllegalArgumentException if {@code string} is invalid
     */
    public ObjId(String string) {
        this(ObjId.parseString(string));
    }

    /**
     * Constructor that reads an encoded instance from the given {@link ByteReader}.
     *
     * @param reader input for binary encoding of an instance
     * @throws IllegalArgumentException if {@code reader} contains invalid data
     */
    public ObjId(ByteReader reader) {
        Preconditions.checkArgument(reader != null, "null reader");
        this.value = ByteUtil.readLong(reader);
        this.validateStorageId();
    }

    /**
     * Constructor using a long value previously returned by {@link #asLong}.
     *
     * @param value long encoding of an instance
     * @throws IllegalArgumentException if {@code value} is invalid
     */
    public ObjId(long value) {
        this.value = value;
        this.validateStorageId();
    }

    private void validateStorageId() {
        final int storageId;
        try {
            storageId = this.getStorageId();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid object ID", e);
        }
        Preconditions.checkArgument(storageId > 0, "invalid object ID containing non-positive storage ID");
    }

// Methods

    /**
     * Get the storage ID associated with this instance. This is the storage ID of the associated object type.
     *
     * @return object type storage ID
     */
    public int getStorageId() {
        return UnsignedIntEncoder.read(new ByteReader(this.getBytes()));
    }

    /**
     * Get the binary encoding of this instance.
     *
     * @return binary encoding
     */
    public byte[] getBytes() {
        final ByteWriter writer = new ByteWriter(NUM_BYTES);
        this.writeTo(writer);
        return writer.getBytes();
    }

    /**
     * Get this instance encoded as a {@code long} value.
     *
     * @return long encoding
     */
    public long asLong() {
        return this.value;
    }

    /**
     * Write the binary encoding of this instance to the given output.
     *
     * @param writer destination
     */
    public void writeTo(ByteWriter writer) {
        ByteUtil.writeLong(writer, this.value);
    }

    /**
     * Determine if this is the <i>sentinel instance</i> for its storage ID.
     * The sentinel instance has trailing bytes all equal to {@code 0x00}.
     *
     * @return true if this is a sentinel instance
     */
    public boolean isSentinel() {
        if (((int)this.value & 0x00ffffff) != 0)                    // quick check
            return false;
        final ByteReader reader = new ByteReader(this.getBytes());
        UnsignedIntEncoder.read(reader);
        while (reader.remain() > 0) {
            if (reader.readByte() != 0)
                return false;
        }
        return true;
    }

    /**
     * Get the smallest (i.e., first) instance having the given storage ID.
     * This is the value having trailing bytes all equal to {@code 0x00}.
     *
     * <p>
     * This value is also known as the {@linkplain #getSentinel sentinel value} for the given storage ID.
     *
     * @param storageId storage ID, must be greater than zero
     * @return smallest instance with storage ID {@code storageId} (inclusive)
     * @throws IllegalArgumentException if {@code storageId} is zero or negative
     * @see #getSentinel getSentinel()
     */
    public static ObjId getMin(int storageId) {
        return ObjId.getFill(storageId, 0x00);
    }

    /**
     * Get the <i>sentinel value</i> for the given storage ID.
     * This is the value having trailing bytes all equal to {@code 0x00}.
     *
     * <p>
     * {@link ObjId}'s created via {@link #ObjId(int)} will never equal this value.
     *
     * @param storageId storage ID, must be greater than zero
     * @return smallest instance with storage ID {@code storageId} (inclusive)
     * @throws IllegalArgumentException if {@code storageId} is zero or negative
     */
    public static ObjId getSentinel(int storageId) {
        return ObjId.getMin(storageId);
    }

    /**
     * Get the largest (i.e., last) instance having the given storage ID.
     * This is the value having trailing bytes all equal to {@code 0xff}.
     *
     * @param storageId storage ID, must be greater than zero
     * @return largest instance with storage ID {@code storageId} (inclusive)
     * @throws IllegalArgumentException if {@code storageId} is zero or negative
     */
    public static ObjId getMax(int storageId) {
        return ObjId.getFill(storageId, 0xff);
    }

    /**
     * Get the {@link KeyRange} containing all object IDs with the given storage ID.
     *
     * @param storageId storage ID, must be greater than zero
     * @return {@link KeyRange} containing all object IDs having the specified type
     * @throws IllegalArgumentException if {@code storageId} is zero or negative
     */
    public static KeyRange getKeyRange(int storageId) {
        Preconditions.checkArgument(storageId > 0, "invalid non-positive storage ID");
        final ByteWriter writer = new ByteWriter(NUM_BYTES);
        UnsignedIntEncoder.write(writer, storageId);
        return KeyRange.forPrefix(writer.getBytes());
    }

    private static ObjId getFill(int storageId, int value) {
        Preconditions.checkArgument(storageId > 0, "invalid non-positive storage ID");
        final ByteWriter writer = new ByteWriter(NUM_BYTES);
        UnsignedIntEncoder.write(writer, storageId);
        for (int remain = NUM_BYTES - writer.getLength(); remain > 0; remain--)
            writer.writeByte(value);
        return new ObjId(new ByteReader(writer));
    }

// Object

    /**
     * Encode this instance as a string.
     */
    @Override
    public String toString() {
        final ByteWriter writer = new ByteWriter(NUM_BYTES);
        this.writeTo(writer);
        final byte[] buf = writer.getBytes();
        final char[] result = new char[NUM_BYTES * 2];
        int off = 0;
        for (byte b : buf) {
            result[off++] = Character.forDigit((b >> 4) & 0x0f, 16);
            result[off++] = Character.forDigit(b & 0x0f, 16);
        }
        return new String(result);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (obj == null || obj.getClass() != this.getClass())
            return false;
        final ObjId that = (ObjId)obj;
        return this.value == that.value;
    }

    /**
     * Returns the hash code value for this instance.
     *
     * <p>
     * The hash code of an {@link ObjId} is defined as the hash code of its {@link #asLong} value,
     * which is {@linkplain Long#hashCode defined} as the exclusive-or of the upper and lower 32 bits.
     */
    @Override
    public int hashCode() {
        return Long.hashCode(this.value);
    }

// Comparable

    @Override
    public int compareTo(ObjId that) {
        return ByteUtil.compare(this.getBytes(), that.getBytes());
    }

// Internal methods

    private static ByteReader buildRandom(int storageId) {
        Preconditions.checkArgument(storageId > 0, "non-positive storage ID");
        final ByteWriter writer = new ByteWriter(NUM_BYTES);
        UnsignedIntEncoder.write(writer, storageId);
        final byte[] randomPart = new byte[NUM_BYTES - writer.getLength()];
    zeroLoop:
        while (true) {
            ObjId.RANDOM.get().nextBytes(randomPart);
            for (byte b : randomPart) {
                if (b != 0)
                    break zeroLoop;
            }
        }
        writer.write(randomPart);
        return new ByteReader(writer);
    }

    private static ByteReader parseString(String string) {
        if (string == null)
            throw new IllegalArgumentException("null string");
        if (string.length() != NUM_BYTES * 2)
            throw new IllegalArgumentException("invalid object ID `" + string + "'");
        final byte[] buf = new byte[NUM_BYTES];
        int off = 0;
        for (int i = 0; i < buf.length; i++) {
            final int digit1 = Character.digit(string.charAt(off++), 16);
            final int digit2 = Character.digit(string.charAt(off++), 16);
            if (digit1 == -1 || digit2 == -1)
                throw new IllegalArgumentException("invalid object ID `" + string + "'");
            buf[i] = (byte)((digit1 << 4) | digit2);
        }
        return new ByteReader(buf);
    }
}

