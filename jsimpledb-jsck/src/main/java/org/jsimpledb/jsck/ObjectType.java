
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.jsck;

import com.google.common.collect.PeekingIterator;

import java.util.HashMap;
import java.util.HashSet;

import org.jsimpledb.core.FieldType;
import org.jsimpledb.core.Layout;
import org.jsimpledb.core.ObjId;
import org.jsimpledb.core.type.EnumFieldType;
import org.jsimpledb.core.type.ReferenceFieldType;
import org.jsimpledb.kv.KVPair;
import org.jsimpledb.schema.CollectionSchemaField;
import org.jsimpledb.schema.ComplexSchemaField;
import org.jsimpledb.schema.CounterSchemaField;
import org.jsimpledb.schema.EnumSchemaField;
import org.jsimpledb.schema.ListSchemaField;
import org.jsimpledb.schema.MapSchemaField;
import org.jsimpledb.schema.ReferenceSchemaField;
import org.jsimpledb.schema.SchemaCompositeIndex;
import org.jsimpledb.schema.SchemaField;
import org.jsimpledb.schema.SchemaFieldSwitchAdapter;
import org.jsimpledb.schema.SchemaObjectType;
import org.jsimpledb.schema.SetSchemaField;
import org.jsimpledb.schema.SimpleSchemaField;
import org.jsimpledb.util.ByteReader;
import org.jsimpledb.util.ByteUtil;
import org.jsimpledb.util.ByteWriter;
import org.jsimpledb.util.UnsignedIntEncoder;

class ObjectType extends Storage {

    private final SchemaObjectType objType;

    // Derived info
    private final HashMap<Integer, FieldType<?>> simpleFieldTypes = new HashMap<>();        // includes sub-fields
    private final HashSet<SimpleSchemaField> indexedSimpleFields = new HashSet<>();         // does not include sub-fields

    ObjectType(final JsckInfo info, final SchemaObjectType objType) {
        super(info, objType.getStorageId());
        this.objType = objType;

        // Get FieldType's for each simple field
        for (SchemaField field : objType.getSchemaFields().values()) {
            field.visit(new SchemaFieldSwitchAdapter<Void>() {
                @Override
                protected Void caseCollectionSchemaField(CollectionSchemaField field) {
                    field.getElementField().visit(this);
                    return null;
                }
                @Override
                public Void caseMapSchemaField(MapSchemaField field) {
                    field.getKeyField().visit(this);
                    field.getValueField().visit(this);
                    return null;
                }
                @Override
                public Void caseSimpleSchemaField(SimpleSchemaField field) {
                    final FieldType<?> fieldType = info.getConfig().getFieldTypeRegistry().getFieldType(field.getType());
                    ObjectType.this.simpleFieldTypes.put(field.getStorageId(), fieldType);
                    return null;
                }
                @Override
                public Void caseEnumSchemaField(EnumSchemaField field) {
                    ObjectType.this.simpleFieldTypes.put(field.getStorageId(), new EnumFieldType(field.getIdentifiers()));
                    return null;
                }
                @Override
                public Void caseReferenceSchemaField(ReferenceSchemaField field) {
                    ObjectType.this.simpleFieldTypes.put(field.getStorageId(), new ReferenceFieldType(field.getObjectTypes()));
                    return null;
                }
                @Override
                public Void caseCounterSchemaField(CounterSchemaField field) {
                    return null;
                }
            });
        }

        // Inventory simple fields that are not sub-fields
        for (SchemaField field : objType.getSchemaFields().values()) {
            field.visit(new SchemaFieldSwitchAdapter<Void>() {
                @Override
                public Void caseSimpleSchemaField(SimpleSchemaField field) {
                    if (field.isIndexed())
                        ObjectType.this.indexedSimpleFields.add(field);
                    return null;
                }
                @Override
                protected Void caseDefault(SchemaField field) {
                    return null;
                }
            });
        }
    }

    /**
     * Validate the encoding of an object.
     *
     * @param info runtime info
     * @param id object ID
     * @param version object schema version
     * @param objType object's schema object type
     * @param i iteration of all key/value pairs having {@code id} as a strict prefix
     * @throws IllegalArgumentException if entry is invalid
     */
    public void validateObjectData(JsckInfo info, ObjId id, int version, PeekingIterator<KVPair> i) {

        // Get object prefix
        final byte[] objectPrefix = id.getBytes();

        // Keep track of which simple fields we see with non-default values
        final HashSet<SimpleSchemaField> indexedSimpleFieldsWithDefaultValues = new HashSet<>(this.indexedSimpleFields);

        // Keep track of simple field values (after possible fixups)
        final HashMap<Integer, byte[]> simpleFieldValues = new HashMap<>();

        // Scan field data; note we will not see simple fields with default values in this loop
        while (i.hasNext()) {
            final KVPair pair = i.peek();
            final byte[] key = pair.getKey();

            // Have we reached the end of the object?
            if (!ByteUtil.isPrefixOf(objectPrefix, key))
                break;
            assert key.length > ObjId.NUM_BYTES;

            // Decode field storage ID
            final ByteReader keyReader = new ByteReader(key, ObjId.NUM_BYTES);
            final int storageId;
            try {
                storageId = UnsignedIntEncoder.read(keyReader);
            } catch (IllegalArgumentException e) {
                info.handle(new InvalidKey(pair).setDetail(id, "invalid field storage ID"));
                continue;
            }
            if (storageId <= 0) {
                info.handle(new InvalidKey(pair).setDetail(id, "invalid field storage ID " + storageId));
                continue;
            }

            // Find the field
            final SchemaField field = objType.getSchemaFields().get(storageId);
            if (field == null) {
                info.handle(new InvalidKey(pair).setDetail(id, "invalid field storage ID "
                  + storageId + ": no such field exists in " + objType));
                continue;
            }

            // Build field prefix
            final byte[] fieldPrefix = keyReader.getBytes(0, keyReader.getOffset());

            // Scan field
            if (info.isDetailEnabled())
                info.detail("checking object " + id + " " + field);
            field.visit(new SchemaFieldSwitchAdapter<Void>() {
                @Override
                public Void caseSimpleSchemaField(SimpleSchemaField field) {
                    final byte[] value = ObjectType.this.checkSimpleField(info, id, field, fieldPrefix, i);
                    simpleFieldValues.put(field.getStorageId(), value);
                    if (value != null)
                        indexedSimpleFieldsWithDefaultValues.remove(field);
                    return null;
                }
                @Override
                public Void caseSetSchemaField(SetSchemaField field) {
                    ObjectType.this.checkSetField(info, id, field, fieldPrefix, i);
                    return null;
                }
                @Override
                public Void caseListSchemaField(ListSchemaField field) {
                    ObjectType.this.checkListField(info, id, field, fieldPrefix, i);
                    return null;
                }
                @Override
                public Void caseMapSchemaField(MapSchemaField field) {
                    ObjectType.this.checkMapField(info, id, field, fieldPrefix, i);
                    return null;
                }
                @Override
                public Void caseCounterSchemaField(CounterSchemaField field) {
                    ObjectType.this.checkCounterField(info, id, field, fieldPrefix, i);
                    return null;
                }
            });
        }
        assert !i.hasNext() || !ByteUtil.isPrefixOf(objectPrefix, i.peek().getKey());

        // Verify index entries for indexed simple fields that had default values (which we would not have encountered)
        for (SimpleSchemaField field : indexedSimpleFieldsWithDefaultValues) {
            final FieldType<?> fieldType = this.simpleFieldTypes.get(field.getStorageId());
            final byte[] defaultValue = fieldType.getDefaultValue();
            this.verifySimpleIndexEntry(info, id, field, defaultValue);
        }

        // Verify composite index entries
        for (SchemaCompositeIndex index : this.objType.getSchemaCompositeIndexes().values())
            this.verifyCompositeIndexEntry(info, id, index, simpleFieldValues);

        // Verify object version index entry
        this.verifyVersionIndexEntry(info, id, version);
    }

    // Returns field's byte[] value if field has non-default value, otherwise null
    private byte[] checkSimpleField(JsckInfo info, ObjId id, SimpleSchemaField field, byte[] prefix, PeekingIterator<KVPair> i) {

        // Get field type
        final FieldType<?> fieldType = this.simpleFieldTypes.get(field.getStorageId());
        assert fieldType != null;

        // Get field key/value pair
        final KVPair pair = i.next();
        assert pair != null;
        assert ByteUtil.isPrefixOf(prefix, pair.getKey());

        // Check for trailing garbage in key
        if (pair.getKey().length > prefix.length) {
            info.handle(new InvalidKey(pair).setDetail(id, field,
              "trailing garbage " + Jsck.ds(new ByteReader(pair.getKey(), prefix.length))));
            return null;
        }

        // Decode value
        byte[] value = pair.getValue();
        final ByteReader reader = new ByteReader(pair.getValue());
        if (!this.validateSimpleFieldValue(info, id, field, pair, reader))
            value = null;

        // We should not see default values in simple fields that are not sub-fields of complex fields
        if (value != null && ByteUtil.compare(value, fieldType.getDefaultValue()) == 0) {
            info.handle(new InvalidValue(pair).setDetail("default value; should not be present"));
            value = null;
        }

        // Verify index entry
        if (field.isIndexed())
            this.verifySimpleIndexEntry(info, id, field, value != null ? value : fieldType.getDefaultValue());

        // Done
        return value;
    }

    private void checkSetField(JsckInfo info, ObjId id, SetSchemaField field, byte[] prefix, PeekingIterator<KVPair> i) {

        // Get element field
        final SimpleSchemaField elementField = field.getElementField();

        // Iterate over set elements
        while (i.hasNext() && ByteUtil.isPrefixOf(prefix, i.peek().getKey())) {
            final KVPair pair = i.next();

            // Verify encoded element
            final ByteReader reader = new ByteReader(pair.getKey(), prefix.length);
            if (!this.validateSimpleFieldValue(info, id, elementField, pair, reader))
                continue;

            // Value should be empty
            if (pair.getValue().length != 0)
                info.handle(new InvalidValue(pair, ByteUtil.EMPTY).setDetail(id, elementField, "should be empty"));

            // Verify index entry
            if (elementField.isIndexed())
                this.verifySimpleIndexEntry(info, id, elementField, field, reader.getBytes(prefix.length), ByteUtil.EMPTY);
        }
    }

    private void checkMapField(JsckInfo info, ObjId id, MapSchemaField field, byte[] prefix, PeekingIterator<KVPair> i) {

        // Get key and value fields
        final SimpleSchemaField keyField = field.getKeyField();
        final SimpleSchemaField valField = field.getValueField();

        // Iterate over set elements
        while (i.hasNext() && ByteUtil.isPrefixOf(prefix, i.peek().getKey())) {
            final KVPair pair = i.next();

            // Verify encoded key
            final ByteReader keyReader = new ByteReader(pair.getKey(), prefix.length);
            if (!this.validateSimpleFieldValue(info, id, keyField, pair, keyReader))
                continue;

            // Verify encoded value
            final ByteReader valReader = new ByteReader(pair.getValue());
            if (!this.validateSimpleFieldValue(info, id, valField, pair, valReader))
                continue;

            // Verify index entries
            if (keyField.isIndexed())
                this.verifySimpleIndexEntry(info, id, keyField, field, keyReader.getBytes(prefix.length), ByteUtil.EMPTY);
            if (valField.isIndexed())
                this.verifySimpleIndexEntry(info, id, valField, field, pair.getValue(), keyReader.getBytes(prefix.length));
        }
    }

    private void checkListField(JsckInfo info, ObjId id, ListSchemaField field, byte[] prefix, PeekingIterator<KVPair> i) {

        // Get element field and type
        final SimpleSchemaField elementField = field.getElementField();

        // Iterate over list elements
        int expectedIndex = 0;
        while (i.hasNext() && ByteUtil.isPrefixOf(prefix, i.peek().getKey())) {
            final KVPair pair = i.next();

            // Decode list index
            final ByteReader keyReader = new ByteReader(pair.getKey(), prefix.length);
            final int actualIndex;
            try {
                try {
                    actualIndex = UnsignedIntEncoder.read(keyReader);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("invalid list index: " + e.getMessage(), e);
                }
                if (keyReader.remain() > 0) {
                    throw new IllegalArgumentException("trailing garbage "
                      + Jsck.ds(keyReader, keyReader.getOffset()) + " after encoded index " + actualIndex);
                }
            } catch (IllegalArgumentException e) {
                info.handle(new InvalidValue(pair).setDetail(id, elementField, e.getMessage()));
                continue;
            }

            // Verify encoded element
            final ByteReader valReader = new ByteReader(pair.getValue());
            if (!this.validateSimpleFieldValue(info, id, elementField, pair, valReader))
                continue;

            // Check list index, and renumber if necessary
            byte[] encodedIndex = keyReader.getBytes(prefix.length);
            if (actualIndex != expectedIndex) {
                info.handle(new InvalidValue(pair).setDetail(id,
                  elementField, "wrong index " + actualIndex + " != " + expectedIndex));
                final ByteWriter keyWriter = new ByteWriter(prefix.length + UnsignedIntEncoder.encodeLength(actualIndex));
                keyWriter.write(prefix);
                UnsignedIntEncoder.write(keyWriter, expectedIndex);
                encodedIndex = keyWriter.getBytes(prefix.length);
                info.handle(new MissingKey("incorrect list index", keyWriter.getBytes(), pair.getValue())
                  .setDetail(id, elementField, "renumbered list index " + actualIndex + " -> " + expectedIndex));
            }

            // Entry is good - we can advance the list index
            expectedIndex++;

            // Verify index entry
            if (elementField.isIndexed())
                this.verifySimpleIndexEntry(info, id, elementField, field, pair.getValue(), encodedIndex);
        }
    }

    private void checkCounterField(JsckInfo info, ObjId id, CounterSchemaField field, byte[] prefix, PeekingIterator<KVPair> i) {

        // Get field key/value pair
        final KVPair pair = i.next();
        assert pair != null;
        assert ByteUtil.isPrefixOf(prefix, pair.getKey());

        // Check for trailing garbage in key
        if (pair.getKey().length > prefix.length) {
            info.handle(new InvalidKey(pair).setDetail(id, field,
              "trailing garbage " + Jsck.ds(new ByteReader(pair.getKey(), prefix.length))));
            return;
        }

        // Decode value
        try {
            info.getKVStore().decodeCounter(pair.getValue());
        } catch (IllegalArgumentException e) {
            info.handle(new InvalidValue(pair).setDetail(id, field, " (resetting to zero): " + e.getMessage()));
        }
    }

    private boolean validateSimpleFieldValue(JsckInfo info, ObjId id, SimpleSchemaField field, KVPair pair, ByteReader reader) {

        // Verify field encoding
        final FieldType<?> fieldType = this.simpleFieldTypes.get(field.getStorageId());
        assert fieldType != null;
        try {

            // Decode value
            final Object value = fieldType.read(reader);
            if (reader.remain() > 0)
                throw new IllegalArgumentException("trailing garbage " + Jsck.ds(reader, reader.getOffset()));

            // For reference fields, check for illegal dangling references
            if (value != null && field instanceof ReferenceSchemaField) {
                final ReferenceSchemaField referenceField = (ReferenceSchemaField)field;
                if (!referenceField.isAllowDeleted()) {
                    assert fieldType instanceof ReferenceFieldType;
                    final ObjId target = (ObjId)value;
                    if (info.getKVStore().get(target.getBytes()) == null)
                        throw new IllegalArgumentException("invalid reference to deleted object " + target);
                }
            }
        } catch (IllegalArgumentException e) {
            info.handle(new InvalidValue(pair).setDetail(id, field, e.getMessage()));
            return false;
        }
        return true;
    }

    private void verifySimpleIndexEntry(JsckInfo info, ObjId id, SimpleSchemaField field, byte[] value) {
        this.verifySimpleIndexEntry(info, id, field.getStorageId(), "" + field + " index", value, ByteUtil.EMPTY);
    }

    private void verifySimpleIndexEntry(JsckInfo info, ObjId id, SimpleSchemaField subField, ComplexSchemaField field,
      byte[] value, byte[] suffix) {
        this.verifySimpleIndexEntry(info, id, subField.getStorageId(),
          "sub-" + subField + " of " + field + " index", value, suffix);
    }

    private void verifySimpleIndexEntry(JsckInfo info, ObjId id, int storageId, String description, byte[] value, byte[] suffix) {

        // Build index entry
        final int length = UnsignedIntEncoder.encodeLength(storageId) + value.length + ObjId.NUM_BYTES + suffix.length;
        final ByteWriter writer = new ByteWriter(length);
        UnsignedIntEncoder.write(writer, storageId);
        writer.write(value);
        id.writeTo(writer);
        writer.write(suffix);

        // Verify index entry
        this.verifyIndexEntry(info, id, writer.getBytes(), description);
    }

    /**
     * Verify a composite index entry.
     */
    private void verifyCompositeIndexEntry(JsckInfo info,
      ObjId id, SchemaCompositeIndex index, HashMap<Integer, byte[]> simpleFieldValues) {

        // Build index entry
        final ByteWriter writer = new ByteWriter();
        UnsignedIntEncoder.write(writer, index.getStorageId());
        for (int storageId : index.getIndexedFields()) {

            // Get the field's value
            byte[] value = simpleFieldValues.get(storageId);
            if (value == null)
                value = this.simpleFieldTypes.get(storageId).getDefaultValue();

            // Append to index entry
            writer.write(value);
        }
        id.writeTo(writer);

        // Verify index entry
        this.verifyIndexEntry(info, id, writer.getBytes(), "" + index);
    }

    private void verifyVersionIndexEntry(JsckInfo info, ObjId id, int version) {

        // Build index entry
        final ByteWriter writer = new ByteWriter();
        writer.write(Layout.getObjectVersionIndexKeyPrefix());
        UnsignedIntEncoder.write(writer, version);
        id.writeTo(writer);

        // Verify index entry
        this.verifyIndexEntry(info, id, writer.getBytes(), "object version index");
    }

    private void verifyIndexEntry(JsckInfo info, ObjId id, byte[] key, String description) {
        if (info.isDetailEnabled())
            info.detail("checking object " + id + " " + description + " entry");
        final byte[] value = info.getKVStore().get(key);
        if (value == null)
            info.handle(new MissingKey("missing index entry for " + description, key, ByteUtil.EMPTY));
        else if (value.length != 0)
            info.handle(new InvalidValue("invalid non-empty value for " + description, key, value, ByteUtil.EMPTY));
    }

// Object

    @Override
    public String toString() {
        return this.objType.toString();
    }
}

