
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.core.util;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

import org.dellroad.stuff.xml.IndentXMLStreamWriter;
import org.jsimpledb.core.CollectionField;
import org.jsimpledb.core.CounterField;
import org.jsimpledb.core.DeletedObjectException;
import org.jsimpledb.core.Field;
import org.jsimpledb.core.FieldType;
import org.jsimpledb.core.ListField;
import org.jsimpledb.core.MapField;
import org.jsimpledb.core.ObjId;
import org.jsimpledb.core.ObjType;
import org.jsimpledb.core.ReferenceField;
import org.jsimpledb.core.Schema;
import org.jsimpledb.core.SetField;
import org.jsimpledb.core.SimpleField;
import org.jsimpledb.core.SnapshotTransaction;
import org.jsimpledb.core.Transaction;
import org.jsimpledb.core.UnknownTypeException;
import org.jsimpledb.schema.NameIndex;
import org.jsimpledb.schema.SchemaField;
import org.jsimpledb.schema.SchemaModel;
import org.jsimpledb.schema.SchemaObjectType;
import org.jsimpledb.util.AbstractXMLStreaming;
import org.jsimpledb.util.ByteWriter;

/**
 * Utility methods for serializing and deserializing objects in a {@link Transaction} to/from XML.
 *
 * <p>
 * There are two supported formats. The "Storage ID Format" specifies objects and fields using their storage IDs
 * and supports all possible database contents.
 *  <pre>
 *  &lt;objects&gt;
 *      &lt;object storageId="100" id="64a971e1aef01cc8" version="1"&gt;
 *          &lt;field storageId="101"&gt;George Washington&lt;/field&gt;
 *          &lt;field storageId="102"&gt;true&lt;/field&gt;
 *          &lt;field storageId="103"&gt;
 *              &lt;entry&gt;
 *                  &lt;key&gt;teeth&lt;/key&gt;
 *                  &lt;value&gt;wooden&lt;/value&gt;
 *              &lt;/entry&gt;
 *          &lt;/field&gt;
 *          &lt;field storageId="104"&gt;c8b84a08e5c2b1a2&lt;/field&gt;
 *      &lt;/object&gt;
 *      ...
 *  &lt;/objects&gt;
 *  </pre>
 *
 * <p>
 * "Name Format" differs from "Storage ID Format" in that object types and fields are specified by name instead of storage ID.
 * However, it does not support schemas that use names that are not valid XML tags.
 * <pre>
 *  &lt;objects&gt;
 *      &lt;Person id="64a971e1aef01cc8" version="1"&gt;
 *          &lt;name&gt;George Washington&lt;/name&gt;
 *          &lt;wasPresident&gt;true&lt;/wasPresident&gt;
 *          &lt;attributes&gt;
 *              &lt;entry&gt;
 *                  &lt;key&gt;teeth&lt;/key&gt;
 *                  &lt;value&gt;wooden&lt;/value&gt;
 *              &lt;/entry&gt;
 *          &lt;/attributes&gt;
 *          &lt;spouse&gt;c8b84a08e5c2b1a2&lt;/spouse&gt;
 *      &lt;/Person&gt;
 *      ...
 *  &lt;/objects&gt;
 *  </pre>
 *
 * <p>
 * Some details on the input logic:
 * <ul>
 *  <li>Simple fields that are equal to their default values, and complex fields that are empty, may be omitted</li>
 *  <li>The {@code "version"} attribute may be omitted, in which case the default schema version associated with
 *      the {@link Transaction} being written to is assumed.</li>
 *  <li>The {@code "id"} attribute may be omitted, in which case a random unassigned ID is generated</li>
 *  <li>Any object ID (including the {@code "id"} attribute) may have the special form
 *      <code>generated:<i>TYPE</i>:<i>SUFFIX</i></code>, where <code><i>TYPE</i></code> is the object type
 *      name or storage ID and <code><i>SUFFIX</i></code> is an arbitrary string.
 *      In this case, a random, unassigned object ID for type <code><i>TYPE</i></code> is generated
 *      on first occurrence, and on subsequent occurences the previously generated ID is recalled. This facilitates
 *      input generated via XSL and the {@code generate-id()} function.
 *      The {@linkplain #getGeneratedIdCache configured} {@link GeneratedIdCache} keeps track of generated IDs.</li>
 *  <li>XML elements and annotations are expected to be in the null namespace; elements and annotations in other
 *      namespaces are ignored</li>
 * </ul>
 */
public class XMLObjectSerializer extends AbstractXMLStreaming {

    /**
     * The supported XML namespace URI.
     *
     * <p>
     * Currently this is {@link XMLConstants#NULL_NS_URI}, i.e., the null/default namespace.
     *
     * <p>
     * XML tags and attributes whose names are in other namespaces are ignored.
     */
    public static final String NS_URI = XMLConstants.NULL_NS_URI;

    public static final QName ELEMENT_TAG = new QName("element");
    public static final QName ENTRY_TAG = new QName("entry");
    public static final QName FIELD_TAG = new QName("field");
    public static final QName KEY_TAG = new QName("key");
    public static final QName OBJECTS_TAG = new QName("objects");
    public static final QName OBJECT_TAG = new QName("object");
    public static final QName VALUE_TAG = new QName("value");

    public static final QName ID_ATTR = new QName("id");
    public static final QName STORAGE_ID_ATTR = new QName("storageId");
    public static final QName VERSION_ATTR = new QName("version");
    public static final QName NULL_ATTR = new QName("null");

    private static final Pattern GENERATED_ID_PATTERN = Pattern.compile("generated:([^:]+):(.*)");

    private final Transaction tx;
    private final HashMap<Integer, NameIndex> nameIndexMap = new HashMap<>();

    private GeneratedIdCache generatedIdCache = new GeneratedIdCache();
    private final ObjIdMap<ReferenceField> unresolvedReferences = new ObjIdMap<>();
    private int fieldTruncationLength = -1;

    /**
     * Constructor.
     *
     * @param tx {@link Transaction} on which to operate
     * @throws IllegalArgumentException if {@code tx} is null
     */
    public XMLObjectSerializer(Transaction tx) {
        Preconditions.checkArgument(tx != null, "null tx");
        this.tx = tx;

        // Build name index for each schema version
        for (Schema schema : this.tx.getSchemas().getVersions().values())
            nameIndexMap.put(schema.getVersionNumber(), new NameIndex(schema.getSchemaModel()));
    }

    /**
     * Get the maximum length (number of characters) of any written simple field.
     *
     * <p>
     * By default, this value is set to {@code -1}, i.e., truncation is disabled.
     *
     * @return maximum simple field length, or zero for empty simple fields, or {@code -1} if truncation is disabled
     * @see #setFieldTruncationLength setFieldTruncationLength()
     */
    public int getFieldTruncationLength() {
        return this.fieldTruncationLength;
    }

    /**
     * Set the maximum length (number of characters) of any written simple field.
     *
     * <p>
     * Simple field values longer than this will be truncated. If set to zero, all simple field values are written as empty tags.
     * If set to {@code -1}, truncation is disabled.
     *
     * <p>
     * Truncation is mainly useful for generating human-readable output without very long lines.
     * Obviously, when truncation is enabled, the resulting output, although still valid XML, will
     * be missing some information and therefore cannot successfully be read back in by this class.
     *
     * @param length maximum simple field length, or zero for empty simple fields, or {@code -1} to disable truncation
     * @throws IllegalArgumentException if {@code length < -1}
     */
    public void setFieldTruncationLength(int length) {
        Preconditions.checkArgument(length >= -1, "length < -1");
        this.fieldTruncationLength = length;
    }

    /**
     * Get all unresolved forward object references.
     *
     * <p>
     * When {@link #read(InputStream, boolean) read()} is invoked with {@code allowUnresolvedReferences = true},
     * unresolved forward object references do not trigger an exception; this allows forward references to span
     * multiple invocations. Instead, these references are collected and made available to the caller in the returned map.
     * Callers may also modify the returned map as desired between invocations.
     *
     * @return mapping from unresolved forward object reference to some referring field
     */
    public ObjIdMap<ReferenceField> getUnresolvedReferences() {
        return this.unresolvedReferences;
    }

    /**
     * Get the {@link GeneratedIdCache} associated with this instance.
     *
     * @return the associated {@link GeneratedIdCache}
     */
    public GeneratedIdCache getGeneratedIdCache() {
        return this.generatedIdCache;
    }

    /**
     * Set the {@link GeneratedIdCache} associated with this instance.
     *
     * @param generatedIdCache the {@link GeneratedIdCache} for this instance to use
     * @throws IllegalArgumentException if {@code generatedIdCache} is null
     */
    public void setGeneratedIdCache(GeneratedIdCache generatedIdCache) {
        Preconditions.checkArgument(generatedIdCache != null, "null generatedIdCache");
        this.generatedIdCache = generatedIdCache;
    }

    /**
     * Import objects pairs into the {@link Transaction} associated with this instance from the given XML input.
     *
     * <p>
     * This is a convenience method, equivalent to:
     * <blockquote><pre>
     * read(input, false)
     * </pre></blockquote>
     *
     * @param input XML input
     * @return the number of objects read
     * @throws XMLStreamException if an error occurs
     * @throws IllegalArgumentException if {@code input} is null
     */
    public int read(InputStream input) throws XMLStreamException {
        return this.read(input, false);
    }

    /**
     * Import objects pairs into the {@link Transaction} associated with this instance from the given XML input.
     *
     * <p>
     * The input format is auto-detected for each {@code <object>} based on the presense of the {@code "type"} attribute.
     *
     * <p>
     * Can optionally check for unresolved object references after reading is complete. If this checking is enabled,
     * an exception is thrown if any unresolved references remain. In any case, the unresolved references are available
     * via {@link #getUnresolvedReferences}.
     *
     * @param input XML input
     * @param allowUnresolvedReferences true to allow unresolved references, false to throw an exception
     * @return the number of objects read
     * @throws XMLStreamException if an error occurs
     * @throws IllegalArgumentException if {@code input} is null
     * @throws DeletedObjectException if {@code allowUnresolvedReferences} is true and any unresolved references
     *  remain when loading is complete
     */
    public int read(InputStream input, boolean allowUnresolvedReferences) throws XMLStreamException {
        Preconditions.checkArgument(input != null, "null input");
        final int count = this.read(XMLInputFactory.newFactory().createXMLStreamReader(input));
        if (!allowUnresolvedReferences && !this.unresolvedReferences.isEmpty()) {
            throw new DeletedObjectException(this.unresolvedReferences.keySet().iterator().next(),
              this.unresolvedReferences.size() + " unresolved reference(s) remain");
        }
        return count;
    }

    /**
     * Import objects into the {@link Transaction} associated with this instance from the given XML input.
     * This method expects to see an opening {@code <objects>} as the next event (not counting whitespace, comments, etc.),
     * which is then consumed up through the closing {@code </objects>} event. Therefore this tag could be part of a
     * larger XML document.
     *
     * <p>
     * The input format is auto-detected for each {@code <object>} based on the presense of the {@code "type"} attribute.
     *
     * @param reader XML reader
     * @return the number of objects read
     * @throws XMLStreamException if an error occurs
     * @throws IllegalArgumentException if {@code reader} is null
     */
    @SuppressWarnings("unchecked")
    public int read(XMLStreamReader reader) throws XMLStreamException {
        Preconditions.checkArgument(reader != null, "null reader");
        this.expect(reader, false, OBJECTS_TAG);

        // Create a snapshot transaction so we can replace objects without triggering DeleteAction's
        final SnapshotTransaction snapshot = this.tx.createSnapshotTransaction();

        // Iterate over objects
        QName name;
        int count;
        for (count = 0; (name = this.next(reader)) != null; count++) {

            // Determine schema version
            final String versionAttr = this.getAttr(reader, VERSION_ATTR, false);
            final Schema schema;
            if (versionAttr != null) {
                try {
                    schema = this.tx.getSchemas().getVersion(Integer.parseInt(versionAttr));
                } catch (IllegalArgumentException e) {
                    throw new XMLStreamException("invalid object schema version `" + versionAttr + "': " + e,
                      reader.getLocation(), e);
                }
            } else
                schema = this.tx.getSchema();
            final NameIndex nameIndex = this.nameIndexMap.get(schema.getVersionNumber());
            final SchemaModel schemaModel = schema.getSchemaModel();

            // Determine object type
            String storageIdAttr = this.getAttr(reader, STORAGE_ID_ATTR, false);
            ObjType objType = null;
            if (storageIdAttr != null) {
                try {
                    objType = schema.getObjType(Integer.parseInt(storageIdAttr));
                } catch (UnknownTypeException e) {
                    throw new XMLStreamException("invalid object type storage ID `" + storageIdAttr + "': " + e,
                      reader.getLocation(), e);
                }
                if (!name.equals(OBJECT_TAG)) {
                    if (!XMLConstants.NULL_NS_URI.equals(name.getNamespaceURI())) {
                        throw new XMLStreamException("unexpected element <" + name.getPrefix() + ":"
                          + name.getLocalPart() + ">; expected <" + OBJECT_TAG.getLocalPart() + ">", reader.getLocation());
                    }
                    if (!objType.getName().equals(name.getLocalPart())) {
                        throw new XMLStreamException("element <" + name.getLocalPart() + "> does not match storage ID "
                          + objType.getStorageId() + "; should be <" + objType.getName() + ">", reader.getLocation());
                    }
                }
            } else {
                if (!XMLConstants.NULL_NS_URI.equals(name.getNamespaceURI())) {
                    throw new XMLStreamException("unexpected element <" + name.getPrefix() + ":"
                      + name.getLocalPart() + ">; expected <object> or object type name", reader.getLocation());
                }
                if (!name.equals(OBJECT_TAG)) {
                    final SchemaObjectType schemaObjectType = nameIndex.getSchemaObjectType(name.getLocalPart());
                    if (schemaObjectType == null) {
                        throw new XMLStreamException("unexpected element <" + name.getLocalPart()
                          + ">; no object type named `" + name.getLocalPart() + "' found in schema version "
                          + schema.getVersionNumber(), reader.getLocation());
                    }
                    objType = schema.getObjType(schemaObjectType.getStorageId());
                }
            }

            // Reset snapshot
            snapshot.reset();

            // Determine object ID and create object in snapshot
            final String idAttr = this.getAttr(reader, ID_ATTR, false);
            ObjId id;
            if (idAttr == null) {

                // Verify we know object type
                if (objType == null) {
                    throw new XMLStreamException("invalid <" + OBJECT_TAG.getLocalPart() + "> element: either \""
                      + STORAGE_ID_ATTR.getLocalPart() + "\" or \"" + ID_ATTR.getLocalPart() + "\" attribute is required",
                      reader.getLocation());
                }

                // Create object
                id = snapshot.create(objType.getStorageId(), schema.getVersionNumber());
            } else {

                // Parse id
                try {
                    id = new ObjId(idAttr);
                } catch (IllegalArgumentException e) {
                    final Matcher matcher = GENERATED_ID_PATTERN.matcher(idAttr);
                    if (!matcher.matches())
                        throw new XMLStreamException("invalid object ID `" + idAttr + "'", reader.getLocation());
                    final String typeAttr = matcher.group(1);
                    final ObjType genType = this.parseGeneratedType(reader, idAttr, typeAttr, schema);
                    if (objType == null)
                        objType = genType;
                    else if (!genType.equals(objType)) {
                        throw new XMLStreamException("type `" + typeAttr + "' in generated object ID `" + idAttr
                          + "' does not match type `" + objType.getName() + "' in schema version "
                          + schema.getVersionNumber(), reader.getLocation());
                    }
                    id = this.generatedIdCache.getGeneratedId(this.tx, objType.getStorageId(), matcher.group(2));
                }

                // Create object
                snapshot.create(id, schema.getVersionNumber());
            }

            // Iterate over fields
            final SchemaObjectType schemaObjectType = schemaModel.getSchemaObjectTypes().get(objType.getStorageId());
            while ((name = this.next(reader)) != null) {
                final QName fieldTagName = name;

                // Determine the field
                storageIdAttr = this.getAttr(reader, STORAGE_ID_ATTR, false);
                final Field<?> field;
                if (storageIdAttr != null) {
                    try {
                        field = objType.getFields().get(Integer.parseInt(storageIdAttr));
                    } catch (IllegalArgumentException e) {
                        throw new XMLStreamException("invalid field storage ID `" + storageIdAttr
                          + "': " + e, reader.getLocation(), e);
                    }
                    if (field == null) {
                        throw new XMLStreamException("unknown field storage ID `" + storageIdAttr + "' for object type `"
                          + objType.getName() + "' #" + objType.getStorageId() + " in schema version "
                          + schema.getVersionNumber(), reader.getLocation());
                    }
                } else {
                    if (!XMLConstants.NULL_NS_URI.equals(name.getNamespaceURI())) {
                        throw new XMLStreamException("unexpected element <" + name.getPrefix() + ":"
                          + name.getLocalPart() + ">; expected field name", reader.getLocation());
                    }
                    final SchemaField schemaField = nameIndex.getSchemaField(schemaObjectType, name.getLocalPart());
                    if (schemaField == null) {
                        throw new XMLStreamException("unexpected element <" + name.getLocalPart() + ">; unknown field `"
                          + name.getLocalPart() + "' in object type `" + objType.getName() + "' #"
                          + objType.getStorageId() + " in schema version " + schema.getVersionNumber(),
                          reader.getLocation());
                    }
                    field = objType.getFields().get(schemaField.getStorageId());
                    assert field != null : "field=" + schemaField + " fields=" + objType.getFields();
                }

                // Parse the field
                if (field instanceof SimpleField)
                    snapshot.writeSimpleField(id, field.getStorageId(), this.readSimpleField(reader, (SimpleField<?>)field), false);
                else if (field instanceof CounterField) {
                    final long value;
                    try {
                        value = Long.parseLong(reader.getElementText());
                    } catch (Exception e) {
                        throw new XMLStreamException("invalid counter value for field `" + field.getName() + "': " + e,
                          reader.getLocation(), e);
                    }
                    snapshot.writeCounterField(id, field.getStorageId(), value, false);
                } else if (field instanceof CollectionField) {
                    final SimpleField<?> elementField = ((CollectionField<?, ?>)field).getElementField();
                    final Collection<?> collection;
                    if (field instanceof SetField)
                        collection = snapshot.readSetField(id, field.getStorageId(), false);
                    else if (field instanceof ListField)
                        collection = snapshot.readListField(id, field.getStorageId(), false);
                    else
                        throw new RuntimeException("internal error: " + field);
                    while ((name = this.next(reader)) != null) {
                        if (!ELEMENT_TAG.equals(name)) {
                            throw new XMLStreamException("invalid field element; expected <" + ELEMENT_TAG.getLocalPart()
                              + "> but found opening <" + name.getLocalPart() + ">", reader.getLocation());
                        }
                        ((Collection<Object>)collection).add(this.readSimpleField(reader, elementField));
                    }
                } else if (field instanceof MapField) {
                    final SimpleField<?> keyField = ((MapField<?, ?>)field).getKeyField();
                    final SimpleField<?> valueField = ((MapField<?, ?>)field).getValueField();
                    final Map<?, ?> map = snapshot.readMapField(id, field.getStorageId(), false);
                    while ((name = this.next(reader)) != null) {
                        if (!ENTRY_TAG.equals(name)) {
                            throw new XMLStreamException("invalid map field entry; expected <" + ENTRY_TAG.getLocalPart()
                              + "> but found opening <" + name.getLocalPart() + ">", reader.getLocation());
                        }
                        if (!KEY_TAG.equals(this.next(reader))) {
                            throw new XMLStreamException("invalid map entry key; expected <" + KEY_TAG.getLocalPart()
                              + ">", reader.getLocation());
                        }
                        final Object key = this.readSimpleField(reader, keyField);
                        if (!VALUE_TAG.equals(this.next(reader))) {
                            throw new XMLStreamException("invalid map entry value; expected <" + VALUE_TAG.getLocalPart()
                              + ">", reader.getLocation());
                        }
                        final Object value = this.readSimpleField(reader, valueField);
                        ((Map<Object, Object>)map).put(key, value);
                        if ((name = this.next(reader)) != null) {
                            throw new XMLStreamException("invalid map field entry; expected closing <" + ENTRY_TAG.getLocalPart()
                              + "> but found opening <" + name.getLocalPart() + ">", reader.getLocation());
                        }
                    }
                } else
                    throw new RuntimeException("internal error: " + field);
            }

            // Copy over object, replacing any previous
            snapshot.copy(id, this.tx, false, false, this.unresolvedReferences, null);

            // Removed the copied object from deleted assignments, as any forward reference to it is now resolved
            this.unresolvedReferences.remove(id);
        }

        // Done
        return count;
    }

    /**
     * Export all objects from the {@link Transaction} associated with this instance to the given output.
     *
     * @param output XML output; will not be closed by this method
     * @param nameFormat true for Name Format, false for Storage ID Format
     * @param indent true to indent output, false for all on one line
     * @return the number of objects written
     * @throws XMLStreamException if an error occurs
     * @throws IllegalArgumentException if {@code output} is null
     */
    public int write(OutputStream output, boolean nameFormat, boolean indent) throws XMLStreamException {
        Preconditions.checkArgument(output != null, "null output");
        XMLStreamWriter xmlWriter = XMLOutputFactory.newInstance().createXMLStreamWriter(output, "UTF-8");
        if (indent)
            xmlWriter = new IndentXMLStreamWriter(xmlWriter);
        xmlWriter.writeStartDocument("UTF-8", "1.0");
        return this.write(xmlWriter, nameFormat);
    }

    /**
     * Export all objects from the {@link Transaction} associated with this instance to the given writer.
     *
     * @param writer XML output; will not be closed by this method
     * @param nameFormat true for Name Format, false for Storage ID Format
     * @param indent true to indent output, false for all on one line
     * @return the number of objects written
     * @throws XMLStreamException if an error occurs
     * @throws IllegalArgumentException if {@code writer} is null
     */
    public int write(Writer writer, boolean nameFormat, boolean indent) throws XMLStreamException {
        Preconditions.checkArgument(writer != null, "null writer");
        XMLStreamWriter xmlWriter = XMLOutputFactory.newInstance().createXMLStreamWriter(writer);
        if (indent)
            xmlWriter = new IndentXMLStreamWriter(xmlWriter);
        xmlWriter.writeStartDocument("1.0");
        return this.write(xmlWriter, nameFormat);
    }

    private int write(XMLStreamWriter writer, boolean nameFormat) throws XMLStreamException {

        // Gather all known object storage IDs
        final TreeSet<Integer> storageIds = new TreeSet<>();
        for (Schema schema : this.tx.getSchemas().getVersions().values())
            storageIds.addAll(schema.getSchemaModel().getSchemaObjectTypes().keySet());

        // Get corresponding object sets
        final ArrayList<NavigableSet<ObjId>> sets = storageIds.stream()
          .map(this.tx::getAll)
          .collect(Collectors.toCollection(() -> new ArrayList<>(storageIds.size())));

        // Output all objects
        return this.write(writer, nameFormat, Iterables.concat(sets));
    }

    /**
     * Export the specified objects from the {@link Transaction} associated with this instance to the given XML output.
     *
     * <p>
     * This method writes a start element as its first action, allowing the output to be embedded into a larger XML document.
     * Callers not embedding the output may with to precede invocation of this method with a call to
     * {@link XMLStreamWriter#writeStartDocument writer.writeStartDocument()}.
     *
     * @param writer XML writer; will not be closed by this method
     * @param nameFormat true for Name Format, false for Storage ID Format
     * @param objIds object IDs
     * @return the number of objects written
     * @throws XMLStreamException if an error occurs
     * @throws IllegalArgumentException if {@code writer} or {@code objIds} is null
     */
    public int write(XMLStreamWriter writer, boolean nameFormat, Iterable<? extends ObjId> objIds) throws XMLStreamException {
        Preconditions.checkArgument(writer != null, "null writer");
        Preconditions.checkArgument(objIds != null, "null objIds");
        writer.setDefaultNamespace(OBJECTS_TAG.getNamespaceURI());
        writer.writeStartElement(OBJECTS_TAG.getNamespaceURI(), OBJECTS_TAG.getLocalPart());
        int count = 0;
        for (ObjId id : objIds) {

            // Get object info
            final int typeStorageId = id.getStorageId();
            final int version = this.tx.getSchemaVersion(id);
            final Schema schema = this.tx.getSchemas().getVersion(version);
            final ObjType objType = schema.getObjType(typeStorageId);

            // Get format info
            final QName objectElement = nameFormat ? new QName(objType.getName()) : OBJECT_TAG;
            final int storageIdAttr = nameFormat ? -1 : typeStorageId;

            // Output fields; if all are default, output empty tag
            boolean tagOutput = false;
            ArrayList<Field<?>> fieldList = new ArrayList<>(objType.getFields().values());
            if (nameFormat)
                Collections.sort(fieldList, Comparator.comparing(Field::getName));
            for (Field<?> field : fieldList) {

                // Determine if field equals its default value; if so, skip it
                if (field.hasDefaultValue(this.tx, id))
                    continue;

                // Output <object> opening tag if not output yet
                if (!tagOutput) {
                    this.writeOpenTag(writer, false, objectElement, storageIdAttr, id, version);
                    tagOutput = true;
                }

                // Get tag name
                final QName fieldTag = nameFormat ? new QName(field.getName()) : FIELD_TAG;

                // Special case for simple fields, which use empty tags when null
                if (field instanceof SimpleField) {
                    final Object value = this.tx.readSimpleField(id, field.getStorageId(), false);
                    if (value == null || this.fieldTruncationLength == 0)
                        writer.writeEmptyElement(fieldTag.getNamespaceURI(), fieldTag.getLocalPart());
                    else
                        writer.writeStartElement(fieldTag.getNamespaceURI(), fieldTag.getLocalPart());
                    if (!nameFormat)
                        this.writeAttribute(writer, STORAGE_ID_ATTR, field.getStorageId());
                    if (value != null && this.fieldTruncationLength != 0) {
                        this.writeSimpleFieldText(writer, (SimpleField<?>)field, value);
                        writer.writeEndElement();
                    } else if (value == null)
                        this.writeAttribute(writer, NULL_ATTR, "true");
                    continue;
                }

                // Output field opening tag
                writer.writeStartElement(fieldTag.getNamespaceURI(), fieldTag.getLocalPart());
                if (!nameFormat)
                    this.writeAttribute(writer, STORAGE_ID_ATTR, field.getStorageId());

                // Output field value
                if (field instanceof CounterField)
                    writer.writeCharacters("" + this.tx.readCounterField(id, field.getStorageId(), false));
                else if (field instanceof CollectionField) {
                    final SimpleField<?> elementField = ((CollectionField<?, ?>)field).getElementField();
                    final Iterable<?> collection = field instanceof SetField ?
                      this.tx.readSetField(id, field.getStorageId(), false) :
                      this.tx.readListField(id, field.getStorageId(), false);
                    for (Object element : collection)
                        this.writeSimpleTag(writer, ELEMENT_TAG, elementField, element);
                } else if (field instanceof MapField) {
                    final SimpleField<?> keyField = ((MapField<?, ?>)field).getKeyField();
                    final SimpleField<?> valueField = ((MapField<?, ?>)field).getValueField();
                    for (Map.Entry<?, ?> entry : this.tx.readMapField(id, field.getStorageId(), false).entrySet()) {
                        writer.writeStartElement(ENTRY_TAG.getNamespaceURI(), ENTRY_TAG.getLocalPart());
                        this.writeSimpleTag(writer, KEY_TAG, keyField, entry.getKey());
                        this.writeSimpleTag(writer, VALUE_TAG, valueField, entry.getValue());
                        writer.writeEndElement();
                    }
                } else
                    throw new RuntimeException("internal error: " + field);

                // Output field closing tag
                writer.writeEndElement();
            }

            // Output empty opening tag if not output yet, otherwise closing tag
            if (!tagOutput)
                this.writeOpenTag(writer, true, objectElement, storageIdAttr, id, version);
            else
                writer.writeEndElement();
            count++;
        }

        // Done
        writer.writeEndElement();
        writer.flush();
        return count;
    }

// Internal methods

    private <T> boolean isDefaultValue(SimpleField<T> field, Object value) throws XMLStreamException {
        final ByteWriter writer = new ByteWriter();
        final FieldType<T> fieldType = field.getFieldType();
        fieldType.write(writer, fieldType.validate(value));
        return Arrays.equals(writer.getBytes(), field.getFieldType().getDefaultValue());
    }

    private void writeSimpleTag(XMLStreamWriter writer, QName tag, SimpleField<?> field, Object value)
      throws XMLStreamException {
        if (value != null && this.fieldTruncationLength != 0) {
            writer.writeStartElement(tag.getNamespaceURI(), tag.getLocalPart());
            this.writeSimpleFieldText(writer, field, value);
            writer.writeEndElement();
        } else {
            writer.writeEmptyElement(tag.getNamespaceURI(), tag.getLocalPart());
            if (value == null)
                this.writeAttribute(writer, NULL_ATTR, "true");
        }
    }

    private <T> void writeSimpleFieldText(XMLStreamWriter writer, SimpleField<T> field, Object value) throws XMLStreamException {
        final FieldType<T> fieldType = field.getFieldType();
        String text = fieldType.toString(fieldType.validate(value));
        final int length = text.length();
        if (this.fieldTruncationLength == -1 || length <= this.fieldTruncationLength) {
            writer.writeCharacters(text);
            return;
        }
        writer.writeCharacters(text.substring(0, this.fieldTruncationLength));
        writer.writeCharacters("...[truncated]");
    }

    private <T> T readSimpleField(XMLStreamReader reader, SimpleField<T> field) throws XMLStreamException {

        // Get field type
        final FieldType<T> fieldType = field.getFieldType();

        // Check for null
        final String nullAttr = this.getAttr(reader, NULL_ATTR, false);
        boolean isNull = false;
        if (nullAttr != null) {
            switch (nullAttr) {
            case "true":
            case "false":
                isNull = Boolean.valueOf(nullAttr);
                break;
            default:
                throw new XMLStreamException("invalid value `" + nullAttr + "' for `" + NULL_ATTR.getLocalPart()
                 + "' attribute: value must be \"true\" or \"false\"", reader.getLocation());
            }
        }

        // Get text content
        final String text;
        try {
            text = reader.getElementText();
        } catch (Exception e) {
            throw new XMLStreamException("invalid value for field `" + field.getName() + "': " + e, reader.getLocation(), e);
        }

        // If null, verify there is no text content
        if (isNull) {
            if (text.length() != 0)
                throw new XMLStreamException("text content not allowed for values with null=\"true\"", reader.getLocation());
            return null;
        }

        // Handle generated ID's for reference fields
        if (field instanceof ReferenceField) {
            final Matcher matcher = GENERATED_ID_PATTERN.matcher(text);
            if (matcher.matches()) {
                final int storageId = this.parseGeneratedType(reader, text, matcher.group(1));
                return fieldType.validate(this.generatedIdCache.getGeneratedId(this.tx, storageId, matcher.group(2)));
            }
        }

        // Parse field value
        try {
            return fieldType.fromString(text);
        } catch (Exception e) {
            throw new XMLStreamException("invalid value `" + text + "' for field `" + field.getName() + "': " + e,
              reader.getLocation(), e);
        }
    }

    // Parse a generated ID type found in a reference
    private int parseGeneratedType(XMLStreamReader reader, String text, String attr) throws XMLStreamException {
        int storageId = -1;
        for (Schema schema : this.tx.getSchemas().getVersions().values()) {
            final ObjType objType;
            try {
                objType = this.parseGeneratedType(reader, text, attr, schema);
            } catch (XMLStreamException e) {
                continue;
            }
            if (storageId != -1 && storageId != objType.getStorageId()) {
                throw new XMLStreamException("invalid object type `" + attr + "' in generated object ID `"
                  + text + "': two or more incompatible object types named `" + attr
                  + "' exist (in different schema versions)", reader.getLocation());
            }
            storageId = objType.getStorageId();
        }
        if (storageId == -1) {
            throw new XMLStreamException("invalid object type `" + attr + "' in generated object ID `"
              + text + "': no such object type found in any schema version", reader.getLocation());
        }
        return storageId;
    }

    // Parse a generated ID type found in an object definition
    private ObjType parseGeneratedType(XMLStreamReader reader, String text, String attr, Schema schema)
      throws XMLStreamException {

        // Try object type name
        final NameIndex nameIndex = this.nameIndexMap.get(schema.getVersionNumber());
        final SchemaObjectType schemaObjectType = nameIndex.getSchemaObjectType(attr);
        if (schemaObjectType != null)
            return schema.getObjType(schemaObjectType.getStorageId());

        // Try storage ID
        final int storageId;
        try {
            storageId = Integer.parseInt(attr);
        } catch (IllegalArgumentException e) {
            throw new XMLStreamException("invalid object type `" + attr + "' in generated object ID `"
              + text + "': no such object type found in schema version " + schema.getVersionNumber(), reader.getLocation());
        }
        try {
            return schema.getObjType(storageId);
        } catch (UnknownTypeException e) {
            throw new XMLStreamException("invalid storage ID " + storageId + " in generated object ID `"
              + text + "': no such object type found in schema version " + schema.getVersionNumber(), reader.getLocation());
        }
    }

    private void writeOpenTag(XMLStreamWriter writer, boolean empty, QName element, int storageId, ObjId id, int version)
      throws XMLStreamException {
        if (empty)
            writer.writeEmptyElement(element.getNamespaceURI(), element.getLocalPart());
        else
            writer.writeStartElement(element.getNamespaceURI(), element.getLocalPart());
        if (storageId != -1)
            this.writeAttribute(writer, STORAGE_ID_ATTR, storageId);
        this.writeAttribute(writer, ID_ATTR, id);
        this.writeAttribute(writer, VERSION_ATTR, version);
    }

    private void writeAttribute(XMLStreamWriter writer, QName attr, Object value) throws XMLStreamException {
        writer.writeAttribute(attr.getNamespaceURI(), attr.getLocalPart(), "" + value);
    }

    /**
     * Skip forward until either the next opening tag is reached, or the currently open tag is closed.
     * This override ignores XML tags that are not in our namespace.
     */
    @Override
    protected QName next(XMLStreamReader reader) throws XMLStreamException {
        while (true) {
            final QName name = super.next(reader);
            if (name == null || NS_URI.equals(name.getNamespaceURI()))
                return name;
            this.skip(reader);
        }
    }
}

