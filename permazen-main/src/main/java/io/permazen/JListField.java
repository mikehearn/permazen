
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package io.permazen;

import com.google.common.base.Converter;
import com.google.common.base.Preconditions;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;

import io.permazen.change.ListFieldAdd;
import io.permazen.change.ListFieldClear;
import io.permazen.change.ListFieldRemove;
import io.permazen.change.ListFieldReplace;
import io.permazen.core.ObjId;
import io.permazen.core.Transaction;
import io.permazen.schema.ListSchemaField;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a list field in a {@link JClass}.
 */
public class JListField extends JCollectionField {

    JListField(Permazen jdb, String name, int storageId,
      io.permazen.annotation.JListField annotation, JSimpleField elementField, String description, Method getter) {
        super(jdb, name, storageId, annotation, elementField, description, getter);
    }

    @Override
    public io.permazen.annotation.JListField getDeclaringAnnotation() {
        return (io.permazen.annotation.JListField)super.getDeclaringAnnotation();
    }

    @Override
    public List<?> getValue(JObject jobj) {
        Preconditions.checkArgument(jobj != null, "null jobj");
        return jobj.getTransaction().readListField(jobj.getObjId(), this.storageId, false);
    }

    @Override
    public <R> R visit(JFieldSwitch<R> target) {
        return target.caseJListField(this);
    }

    @Override
    ListSchemaField toSchemaItem(Permazen jdb) {
        final ListSchemaField schemaField = new ListSchemaField();
        super.initialize(jdb, schemaField);
        return schemaField;
    }

    @Override
    ListElementIndexInfo toIndexInfo(JSimpleField subField) {
        assert subField == this.elementField;
        return new ListElementIndexInfo(this);
    }

    @Override
    @SuppressWarnings("serial")
    <E> TypeToken<List<E>> buildTypeToken(TypeToken<E> elementType) {
        return new TypeToken<List<E>>() { }.where(new TypeParameter<E>() { }, elementType);
    }

    // This method exists solely to bind the generic type parameters
    @Override
    @SuppressWarnings("serial")
    <T, E> void addChangeParameterTypes(List<TypeToken<?>> types, Class<T> targetType, TypeToken<E> elementType) {
        types.add(new TypeToken<ListFieldAdd<T, E>>() { }
          .where(new TypeParameter<T>() { }, targetType)
          .where(new TypeParameter<E>() { }, elementType.wrap()));
        types.add(new TypeToken<ListFieldClear<T>>() { }
          .where(new TypeParameter<T>() { }, targetType));
        types.add(new TypeToken<ListFieldRemove<T, E>>() { }
          .where(new TypeParameter<T>() { }, targetType)
          .where(new TypeParameter<E>() { }, elementType.wrap()));
        types.add(new TypeToken<ListFieldReplace<T, E>>() { }
          .where(new TypeParameter<T>() { }, targetType)
          .where(new TypeParameter<E>() { }, elementType.wrap()));
    }

    @Override
    public ListConverter<?, ?> getConverter(JTransaction jtx) {
        final Converter<?, ?> elementConverter = this.elementField.getConverter(jtx);
        return elementConverter != null ? this.createConverter(elementConverter) : null;
    }

    // This method exists solely to bind the generic type parameters
    private <X, Y> ListConverter<X, Y> createConverter(Converter<X, Y> elementConverter) {
        return new ListConverter<>(elementConverter);
    }

// POJO import/export

    @Override
    List<Object> createPojoCollection(Class<?> collectionType) {
        return new ArrayList<>();
    }

    @Override
    List<?> readCoreCollection(Transaction tx, ObjId id) {
        return tx.readListField(id, this.storageId, true);
    }

// Bytecode generation

    @Override
    Method getFieldReaderMethod() {
        return ClassGenerator.JTRANSACTION_READ_LIST_FIELD_METHOD;
    }
}

