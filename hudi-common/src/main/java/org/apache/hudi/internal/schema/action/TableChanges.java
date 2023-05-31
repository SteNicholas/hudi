/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.internal.schema.action;

import org.apache.hudi.internal.schema.HoodieSchemaException;
import org.apache.hudi.internal.schema.InternalSchema;
import org.apache.hudi.internal.schema.InternalSchemaBuilder;
import org.apache.hudi.internal.schema.Type;
import org.apache.hudi.internal.schema.Types;
import org.apache.hudi.internal.schema.utils.SchemaChangeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Operations of schema changes supported in schema evolution
 */
public class TableChanges {

  /** Deal with update columns changes for table. */
  public static class ColumnUpdateChange extends TableChange.BaseColumnChange {
    private final Map<Integer, Types.Field> updates = new HashMap<>();

    private ColumnUpdateChange(InternalSchema schema) {
      super(schema, false);
    }

    private ColumnUpdateChange(InternalSchema schema, boolean caseSensitive) {
      super(schema, caseSensitive);
    }

    @Override
    public boolean withPositionChange() {
      return true;
    }

    public Type applyUpdates(Types.Field oldField, Type type) {
      Types.Field update = updates.get(oldField.fieldId());
      if (update != null && update.type() != oldField.type()) {
        return update.type();
      }
      //
      ArrayList<ColumnPositionChange> pchanges = positionChangeMap.getOrDefault(oldField.fieldId(), new ArrayList<>());
      if (!pchanges.isEmpty()) {
        // when we build ColumnAddChange，we have already done some check, so it's safe to convert newType to RecordType
        List<Types.Field> newFields = TableChangesHelper.applyAddChange2Fields(((Types.RecordType) type).fields(), new ArrayList<>(), pchanges);
        return Types.RecordType.get(newFields);
      }
      return type;
    }

    public Map<Integer, Types.Field> getUpdates() {
      return updates;
    }

    /**
     * Update a column in the schema to a new type.
     * only support update primitive type.
     * Only updates that widen types are allowed.
     *
     * @param name name of the column to update
     * @param newType new type for the column
     * @return this
     * @throws IllegalArgumentException
     */
    public ColumnUpdateChange updateColumnType(String name, Type newType) {
      checkColModifyIsLegal(name);
      if (newType.isNestedType()) {
        throw new IllegalArgumentException(String.format("only support update primitive type but find nest column: %s", name));
      }
      Types.Field field = internalSchema.findField(name);
      if (field == null) {
        throw new IllegalArgumentException(String.format("cannot update a missing column: %s", name));
      }

      if (!SchemaChangeUtils.isTypeUpdateAllow(field.type(), newType)) {
        throw new IllegalArgumentException(String.format("cannot update origin type: %s to a incompatibility type: %s", field.type(), newType));
      }

      if (field.type().equals(newType)) {
        // do nothings
        return this;
      }
      // save update info
      Types.Field update = updates.get(field.fieldId());
      if (update == null) {
        updates.put(field.fieldId(), Types.Field.get(field.fieldId(), field.isOptional(), field.name(), newType, field.doc(), field.getDefaultValue()));
      } else {
        updates.put(field.fieldId(), Types.Field.get(field.fieldId(), update.isOptional(), update.name(), newType, update.doc(), update.getDefaultValue()));
      }
      return this;
    }

    /**
     * Update a column doc in the schema to a new primitive type.
     *
     * @param name name of the column to update
     * @param newDoc new documentation for the column
     * @return this
     * @throws IllegalArgumentException
     */
    public ColumnUpdateChange updateColumnComment(String name, String newDoc) {
      checkColModifyIsLegal(name);
      Types.Field field = internalSchema.findField(name);
      if (field == null) {
        throw new IllegalArgumentException(String.format("cannot update a missing column: %s", name));
      }
      // consider null
      if (Objects.equals(field.doc(), newDoc)) {
        // do nothings
        return this;
      }
      // save update info
      Types.Field update = updates.get(field.fieldId());
      if (update == null) {
        updates.put(field.fieldId(), Types.Field.get(field.fieldId(), field.isOptional(), field.name(), field.type(), newDoc, field.getDefaultValue()));
      } else {
        updates.put(field.fieldId(), Types.Field.get(field.fieldId(), update.isOptional(), update.name(), update.type(), newDoc, update.getDefaultValue()));
      }
      return this;
    }

    /**
     * Update a column default value in the schema to a new primitive type.
     *
     * @param name            name of the column to update
     * @param newDefaultValue new default value for the column
     * @return this
     * @throws IllegalArgumentException
     */
    public ColumnUpdateChange updateColumnDefaultValue(String name, Object newDefaultValue) {
      checkColModifyIsLegal(name);
      Types.Field field = internalSchema.findField(name);
      if (field == null) {
        throw new IllegalArgumentException(String.format("cannot update a missing column: %s", name));
      }
      // consider null
      if (Objects.equals(field.getDefaultValue(), newDefaultValue)) {
        // do nothings
        return this;
      }
      // save update info
      Types.Field update = updates.get(field.fieldId());
      if (update == null) {
        updates.put(field.fieldId(), Types.Field.get(field.fieldId(), field.isOptional(), field.name(), field.type(), field.doc(), newDefaultValue));
      } else {
        updates.put(field.fieldId(), Types.Field.get(field.fieldId(), update.isOptional(), update.name(), update.type(), update.doc(), newDefaultValue));
      }
      return this;
    }

    /**
     * Rename a column in the schema.
     *
     * @param name name of the column to rename
     * @param newName new name for the column
     * @return this
     * @throws IllegalArgumentException
     */
    public ColumnUpdateChange renameColumn(String name, String newName) {
      checkColModifyIsLegal(name);
      Types.Field field = internalSchema.findField(name);
      if (field == null) {
        throw new IllegalArgumentException(String.format("cannot update a missing column: %s", name));
      }
      if (newName == null || newName.isEmpty()) {
        throw new IllegalArgumentException(String.format("cannot rename column: %s to empty", name));
      }
      if (internalSchema.hasColumn(newName, caseSensitive)) {
        throw new IllegalArgumentException(String.format("cannot rename column: %s to a existing name", name));
      }
      // save update info
      Types.Field update = updates.get(field.fieldId());
      if (update == null) {
        updates.put(field.fieldId(), Types.Field.get(field.fieldId(), field.isOptional(), newName, field.type(), field.doc(), field.getDefaultValue()));
      } else {
        updates.put(field.fieldId(), Types.Field.get(field.fieldId(), update.isOptional(), newName, update.type(), update.doc(), update.getDefaultValue()));
      }
      return this;
    }

    /**
     * Update nullable for column.
     * only support required type -> optional type
     *
     * @param name name of the column to update
     * @param nullable nullable for updated name
     * @return this
     * @throws IllegalArgumentException
     */
    public ColumnUpdateChange updateColumnNullability(String name, boolean nullable) {
      return updateColumnNullability(name, nullable, false);
    }

    public ColumnUpdateChange updateColumnNullability(String name, boolean nullable, boolean force) {
      checkColModifyIsLegal(name);
      Types.Field field = internalSchema.findField(name);
      if (field == null) {
        throw new IllegalArgumentException(String.format("cannot update a missing column: %s", name));
      }
      if (field.isOptional() == nullable) {
        // do nothings
        return this;
      }
      if (field.isOptional() && !nullable && !force) {
        throw new IllegalArgumentException("cannot update column Nullability: optional to required");
      }
      // save update info
      Types.Field update = updates.get(field.fieldId());
      if (update == null) {
        updates.put(field.fieldId(), Types.Field.get(field.fieldId(), nullable, field.name(), field.type(), field.doc(), field.getDefaultValue()));
      } else {
        updates.put(field.fieldId(), Types.Field.get(field.fieldId(), nullable, update.name(), update.type(), update.doc(), update.getDefaultValue()));
      }

      return this;
    }

    public Map<Integer, ArrayList<ColumnPositionChange>> getPositionChangeMap() {
      return positionChangeMap;
    }

    @Override
    public ColumnChangeID columnChangeId() {
      return ColumnChangeID.UPDATE;
    }

    @Override
    protected Integer findIdByFullName(String fullName) {
      Types.Field field = internalSchema.findField(fullName);
      if (field != null) {
        return field.fieldId();
      } else {
        throw new IllegalArgumentException(String.format("cannot find col id for given column fullName: %s", fullName));
      }
    }

    public static ColumnUpdateChange get(InternalSchema schema) {
      return new ColumnUpdateChange(schema);
    }

    public static ColumnUpdateChange get(InternalSchema schema, boolean caseSensitive) {
      return new ColumnUpdateChange(schema, caseSensitive);
    }
  }

  /** Deal with delete columns changes for table. */
  public static class ColumnDeleteChange extends TableChange.BaseColumnChange {
    private final Set deletes = new HashSet<>();

    @Override
    public ColumnChangeID columnChangeId() {
      return ColumnChangeID.DELETE;
    }

    public static ColumnDeleteChange get(InternalSchema schema) {
      return new ColumnDeleteChange(schema);
    }

    private ColumnDeleteChange(InternalSchema schema) {
      super(schema);
    }

    @Override
    public boolean withPositionChange() {
      return false;
    }

    @Override
    public BaseColumnChange addPositionChange(String srcId, String dsrId, String orderType) {
      throw new UnsupportedOperationException("no support add position change for ColumnDeleteChange");
    }

    public ColumnDeleteChange deleteColumn(String name) {
      checkColModifyIsLegal(name);
      Types.Field field = internalSchema.findField(name);
      if (field == null) {
        throw new IllegalArgumentException(String.format("cannot delete missing columns: %s", name));
      }
      deletes.add(field.fieldId());
      return this;
    }

    public Type applyDelete(int id, Type type) {
      if (deletes.contains(id)) {
        return null;
      }
      return type;
    }

    public Set<Integer> getDeletes() {
      return deletes;
    }

    @Override
    protected Integer findIdByFullName(String fullName) {
      throw new UnsupportedOperationException("delete change cannot support this method");
    }
  }

  /**
   * Deal with add columns changes for table.
   */
  public static class ColumnAddChange extends TableChange.BaseColumnChange {
    private final Map<String, Integer> fullColName2Id = new HashMap<>();
    private final Map<Integer, ArrayList<Types.Field>> parentId2AddCols = new HashMap<>();
    private int nextId;

    public static ColumnAddChange get(InternalSchema internalSchema) {
      return new ColumnAddChange(internalSchema);
    }

    public Type applyAdd(Types.Field originalField, Type type) {
      int fieldId = originalField.fieldId();
      ArrayList<Types.Field> addFields = parentId2AddCols.getOrDefault(fieldId, new ArrayList<>());
      ArrayList<ColumnPositionChange> pchanges = positionChangeMap.getOrDefault(fieldId, new ArrayList<>());

      if (!addFields.isEmpty() || !pchanges.isEmpty()) {
        // when we build ColumnAddChange，we have already done some check, so it's safe to convert newType to RecordType
        List<Types.Field> newFields = TableChangesHelper.applyAddChange2Fields(((Types.RecordType) type).fields(), addFields, pchanges);
        return Types.RecordType.get(newFields);
      }
      return type;
    }

    public ColumnAddChange addColumns(String name, Type type, String doc, Object defaultValue) {
      return addColumns("", name, type, doc, defaultValue);
    }

    public ColumnAddChange addColumns(String parent, String name, Type type, String doc, Object defaultValue) {
      checkColModifyIsLegal(name);
      addColumnsInternal(parent, name, type, doc, defaultValue);
      return this;
    }

    private void addColumnsInternal(String parent, String name, Type type, String doc, Object defaultValue) {
      // root record has no parent, so set parentId to -1 as default
      int parentId = -1;
      // do check
      String fullName = name;
      if (!parent.isEmpty()) {
        Types.Field parentField = internalSchema.findField(parent);
        if (parentField == null) {
          throw new HoodieSchemaException(String.format("cannot add column: %s which parent: %s is not exist", name, parent));
        }
        if (!(parentField.type() instanceof Types.RecordType)) {
          throw new HoodieSchemaException("only support add nested columns to struct column");
        }
        parentId = parentField.fieldId();
        Types.Field newParentField = internalSchema.findField(parent + "."  + name);
        if (newParentField != null) {
          throw new HoodieSchemaException(String.format("cannot add column: %s which already exist", name));
        }
        fullName = parent + "." + name;
      } else {
        if (internalSchema.hasColumn(name, caseSensitive)) {
          throw new HoodieSchemaException(String.format("cannot add column: %s which already exist", name));
        }
      }
      if (fullColName2Id.containsKey(fullName)) {
        throw new HoodieSchemaException(String.format("cannot repeat add column: %s", name));
      }
      fullColName2Id.put(fullName, nextId);
      if (parentId != -1) {
        id2parent.put(nextId, parentId);
      }
      AtomicInteger assignNextId = new AtomicInteger(nextId + 1);
      Type typeWithNewId = InternalSchemaBuilder.getBuilder().refreshNewId(type, assignNextId);
      // only allow add optional columns.
      ArrayList<Types.Field> adds = parentId2AddCols.getOrDefault(parentId, new ArrayList<>());
      adds.add(Types.Field.get(nextId, true, name, typeWithNewId, doc, defaultValue));
      parentId2AddCols.put(parentId, adds);
      nextId = assignNextId.get();
    }

    private ColumnAddChange(InternalSchema internalSchema) {
      super(internalSchema);
      this.nextId = internalSchema.getMaxColumnId() + 1;
    }

    public Map<Integer, ArrayList<Types.Field>> getParentId2AddCols() {
      return parentId2AddCols;
    }

    public Map<Integer, ArrayList<ColumnPositionChange>> getPositionChangeMap() {
      return positionChangeMap;
    }

    // expose to test
    public Map<String, Integer> getFullColName2Id() {
      return fullColName2Id;
    }

    protected Integer findIdByFullName(String fullName) {
      Types.Field field = internalSchema.findField(fullName);
      if (field != null) {
        return field.fieldId();
      }
      return fullColName2Id.getOrDefault(fullName, -1);
    }

    @Override
    public ColumnChangeID columnChangeId() {
      return ColumnChangeID.ADD;
    }

    @Override
    public boolean withPositionChange() {
      return true;
    }
  }
}

