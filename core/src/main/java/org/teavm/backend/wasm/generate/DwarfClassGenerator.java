/*
 *  Copyright 2022 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.backend.wasm.generate;

import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_ATE_BOOLEAN;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_ATE_FLOAT;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_ATE_SIGNED;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_ATE_UTF;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_AT_BYTE_SIZE;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_AT_DECLARATION;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_AT_ENCODING;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_AT_LOCATION;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_AT_NAME;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_AT_TYPE;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_FORM_DATA1;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_FORM_DATA2;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_FORM_EXPRLOC;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_FORM_FLAG_PRESENT;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_FORM_REF4;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_FORM_STRP;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_OP_ADDR;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_OP_STACK_VALUE;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_TAG_BASE_TYPE;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_TAG_CLASS_TYPE;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_TAG_INHERITANCE;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_TAG_NAMESPACE;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_TAG_POINTER_TYPE;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_TAG_SUBPROGRAM;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_TAG_UNSPECIFIED_TYPE;
import static org.teavm.backend.wasm.dwarf.DwarfConstants.DW_TAG_VARIABLE;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.teavm.backend.wasm.blob.Blob;
import org.teavm.backend.wasm.dwarf.DwarfAbbreviation;
import org.teavm.backend.wasm.dwarf.DwarfInfoWriter;
import org.teavm.backend.wasm.dwarf.DwarfPlaceholder;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.PrimitiveType;
import org.teavm.model.ValueType;
import org.teavm.model.util.VariableType;

public class DwarfClassGenerator {
    private static final ValueType objectType = ValueType.object("java.lang.Object");
    final Namespace root = new Namespace(null);
    final Map<String, Subprogram> subprogramsByFunctionName = new HashMap<>();
    final List<Subprogram> rootSubprograms = new ArrayList<>();
    private final DwarfInfoWriter writer;
    private final DwarfStrings strings;
    private DwarfAbbreviation nsAbbrev;
    private DwarfAbbreviation classTypeAbbrev;
    private DwarfAbbreviation inheritanceAbbrev;
    private DwarfAbbreviation methodAbbrev;
    private DwarfPlaceholder[] primitiveTypes = new DwarfPlaceholder[PrimitiveType.values().length];
    private DwarfPlaceholder unspecifiedType;
    private DwarfAbbreviation baseTypeAbbrev;
    private DwarfAbbreviation pointerAbbrev;
    private DwarfAbbreviation variableAbbrev;
    private List<Runnable> postponedWrites = new ArrayList<>();
    private ClassType classClass;

    public DwarfClassGenerator(DwarfInfoWriter writer, DwarfStrings strings) {
        this.writer = writer;
        this.strings = strings;
    }

    public void flushTypes() {
        for (var postponedWrite : postponedWrites) {
            postponedWrite.run();
        }
        postponedWrites.clear();
    }

    public ClassType getClass(String fullName) {
        var index = 0;
        var ns = root;
        while (true) {
            var next = fullName.indexOf('.', index);
            if (next < 0) {
                break;
            }
            ns = ns.getNamespace(fullName.substring(index, next));
            index = next + 1;
        }
        return ns.getClass(fullName.substring(index));
    }

    public void registerSubprogram(String functionName, Subprogram subprogram) {
        subprogramsByFunctionName.put(functionName, subprogram);
    }

    public Subprogram getSubprogram(String functionName) {
        return subprogramsByFunctionName.get(functionName);
    }

    public void write() {
        classClass = getClass("java.lang.Class");
        root.writeChildren();
        for (var subprogram : rootSubprograms) {
            subprogram.write();
        }
        flushTypes();
    }

    private DwarfAbbreviation getMethodAbbrev() {
        if (methodAbbrev == null) {
            methodAbbrev = writer.abbreviation(DW_TAG_SUBPROGRAM, true, data -> {
                data.writeLEB(DW_AT_NAME).writeLEB(DW_FORM_STRP);
                data.writeLEB(DW_AT_DECLARATION).writeLEB(DW_FORM_FLAG_PRESENT);
            });
        }
        return methodAbbrev;
    }

    private DwarfAbbreviation getNsAbbrev() {
        if (nsAbbrev == null) {
            nsAbbrev = writer.abbreviation(DW_TAG_NAMESPACE, true, data -> {
                data.writeLEB(DW_AT_NAME).writeLEB(DW_FORM_STRP);
            });
        }
        return nsAbbrev;
    }

    private DwarfAbbreviation getClassTypeAbbrev() {
        if (classTypeAbbrev == null) {
            classTypeAbbrev = writer.abbreviation(DW_TAG_CLASS_TYPE, true, data -> {
                data.writeLEB(DW_AT_NAME).writeLEB(DW_FORM_STRP);
                data.writeLEB(DW_AT_BYTE_SIZE).writeLEB(DW_FORM_DATA2);
            });
        }
        return classTypeAbbrev;
    }

    private DwarfAbbreviation getInheritanceAbbrev() {
        if (inheritanceAbbrev == null) {
            inheritanceAbbrev = writer.abbreviation(DW_TAG_INHERITANCE, false, data -> {
                data.writeLEB(DW_AT_TYPE).writeLEB(DW_FORM_REF4);
            });
        }
        return inheritanceAbbrev;
    }

    public DwarfPlaceholder getTypePtr(VariableType type) {
        switch (type) {
            case INT:
                return getPrimitivePtr(ValueType.Primitive.INTEGER);
            case LONG:
                return getPrimitivePtr(ValueType.Primitive.LONG);
            case FLOAT:
                return getPrimitivePtr(ValueType.Primitive.FLOAT);
            case DOUBLE:
                return getPrimitivePtr(ValueType.Primitive.DOUBLE);
            default:
                return getTypePtr(objectType);
        }
    }

    public DwarfPlaceholder getTypePtr(ValueType type) {
        if (type instanceof ValueType.Primitive) {
            return getPrimitivePtr((ValueType.Primitive) type);
        } else if (type instanceof ValueType.Object) {
            return getClassType(((ValueType.Object) type).getClassName());
        }
        return getClassType("java.lang.Object");
    }

    private DwarfPlaceholder getUnspecifiedType() {
        if (unspecifiedType == null) {
            unspecifiedType = writer.placeholder(4);
            var abbrev = writer.abbreviation(DW_TAG_UNSPECIFIED_TYPE, false, blob -> {
                blob.writeInt(DW_AT_NAME).writeInt(DW_FORM_STRP);
            });
            writer.mark(unspecifiedType).tag(abbrev).writeInt(strings.stringRef("<unspecified>"));
        }
        return unspecifiedType;
    }

    private DwarfPlaceholder getClassType(String name) {
        return getClass(name).ptr;
    }

    private DwarfPlaceholder getPrimitivePtr(ValueType.Primitive type) {
        var result = primitiveTypes[type.getKind().ordinal()];
        if (result == null) {
            String name;
            int byteSize;
            int encoding;
            switch (type.getKind()) {
                case BOOLEAN:
                    name = "boolean";
                    byteSize = 1;
                    encoding = DW_ATE_BOOLEAN;
                    break;
                case BYTE:
                    name = "byte";
                    byteSize = 1;
                    encoding = DW_ATE_SIGNED;
                    break;
                case SHORT:
                    name = "short";
                    byteSize = 2;
                    encoding = DW_ATE_SIGNED;
                    break;
                case CHARACTER:
                    name = "char";
                    byteSize = 2;
                    encoding = DW_ATE_UTF;
                    break;
                case INTEGER:
                    name = "int";
                    byteSize = 4;
                    encoding = DW_ATE_SIGNED;
                    break;
                case LONG:
                    name = "long";
                    byteSize = 8;
                    encoding = DW_ATE_SIGNED;
                    break;
                case FLOAT:
                    name = "float";
                    encoding = DW_ATE_FLOAT;
                    byteSize = 4;
                    break;
                case DOUBLE:
                    name = "double";
                    encoding = DW_ATE_FLOAT;
                    byteSize = 8;
                    break;
                default:
                    throw new IllegalArgumentException();
            }
            var ptr = writer.placeholder(4);
            postponedWrites.add(() -> {
                writer.mark(ptr).tag(getBaseTypeAbbrev());
                writer.writeInt(strings.stringRef(name));
                writer.writeByte(byteSize);
                writer.writeByte(encoding);
            });
            result = ptr;
            primitiveTypes[type.getKind().ordinal()] = ptr;
        }

        return result;
    }

    private DwarfAbbreviation getBaseTypeAbbrev() {
        if (baseTypeAbbrev == null) {
            baseTypeAbbrev = writer.abbreviation(DW_TAG_BASE_TYPE, false, blob -> {
                blob.writeLEB(DW_AT_NAME).writeLEB(DW_FORM_STRP);
                blob.writeLEB(DW_AT_BYTE_SIZE).writeLEB(DW_FORM_DATA1);
                blob.writeLEB(DW_AT_ENCODING).writeLEB(DW_FORM_DATA1);
            });
        }
        return baseTypeAbbrev;
    }

    private DwarfAbbreviation getPointerAbbrev() {
        if (pointerAbbrev == null) {
            pointerAbbrev = writer.abbreviation(DW_TAG_POINTER_TYPE, false, blob -> {
                blob.writeLEB(DW_AT_TYPE).writeLEB(DW_FORM_REF4);
            });
        }
        return pointerAbbrev;
    }

    private DwarfAbbreviation getVariableAbbrev() {
        if (variableAbbrev == null) {
            variableAbbrev = writer.abbreviation(DW_TAG_VARIABLE, false, blob -> {
                blob.writeLEB(DW_AT_NAME).writeLEB(DW_FORM_STRP);
                blob.writeLEB(DW_AT_TYPE).writeLEB(DW_FORM_REF4);
                blob.writeLEB(DW_AT_LOCATION).writeLEB(DW_FORM_EXPRLOC);
            });
        }
        return variableAbbrev;
    }

    public class Namespace {
        public final String name;
        final Map<String, Namespace> namespaces = new LinkedHashMap<>();
        final Map<String, ClassType> classes = new LinkedHashMap<>();

        private Namespace(String name) {
            this.name = name;
        }

        private Namespace getNamespace(String name) {
            return namespaces.computeIfAbsent(name, Namespace::new);
        }

        private void write() {
            writer.tag(getNsAbbrev());
            writer.writeInt(strings.stringRef(name));
            writeChildren();
            writer.emptyTag();
        }

        private void writeChildren() {
            for (var child : namespaces.values()) {
                child.write();
            }
            for (var child : classes.values()) {
                child.write();
            }
        }

        ClassType getClass(String name) {
            return classes.computeIfAbsent(name, ClassType::new);
        }
    }

    public class ClassType {
        public final String name;
        final DwarfPlaceholder ptr;
        private DwarfPlaceholder pointerPtr;
        final Map<MethodDescriptor, Subprogram> subprograms = new LinkedHashMap<>();
        private ClassType superclass;
        private int size;
        private int pointer = -1;

        private ClassType(String name) {
            ptr = writer.placeholder(4);
            this.name = name;
        }

        public void setSuperclass(ClassType superclass) {
            this.superclass = superclass;
        }

        public Subprogram getSubprogram(MethodDescriptor desc) {
            return subprograms.computeIfAbsent(desc, d -> new Subprogram(d.getName(), desc));
        }

        public void setSize(int size) {
            this.size = size;
        }

        public void setPointer(int pointer) {
            this.pointer = pointer;
        }

        public DwarfPlaceholder getPointerPtr() {
            if (pointerPtr == null) {
                pointerPtr = writer.placeholder(4);
            }
            return pointerPtr;
        }

        private void write() {
            writer.mark(ptr).tag(getClassTypeAbbrev());
            writer.writeInt(strings.stringRef(name));
            writer.writeShort(size);
            if (superclass != null) {
                writer.tag(getInheritanceAbbrev()).ref(superclass.ptr, Blob::writeInt);
            }
            for (var child : subprograms.values()) {
                child.write();
            }
            if (pointerPtr != null) {
                writer.mark(pointerPtr).tag(getPointerAbbrev());
                writer.ref(ptr, Blob::writeInt);
            }
            if (pointer >= 0) {
                writer.tag(getVariableAbbrev());
                writer.writeInt(strings.stringRef("__class__"));
                writer.ref(classClass.ptr, Blob::writeInt);
                var ops = new Blob();
                ops.writeByte(DW_OP_ADDR).writeInt(pointer).writeByte(DW_OP_STACK_VALUE);
                writer.writeLEB(ops.size());
                ops.newReader(writer::write).readRemaining();
            }
            writer.emptyTag();
        }
    }

    public class Subprogram {
        public final String name;
        public boolean isStatic;
        public final MethodDescriptor descriptor;
        public final DwarfPlaceholder ref;

        private Subprogram(String name, MethodDescriptor descriptor) {
            this.name = name;
            this.descriptor = descriptor;
            ref = writer.placeholder(4);
        }

        private void write() {
            writer.mark(ref).tag(getMethodAbbrev());
            writer.writeInt(strings.stringRef(name));
            writer.emptyTag();
        }
    }
}
