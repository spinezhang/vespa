// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.serialization;

import com.yahoo.compress.Compressor;
import com.yahoo.document.*;
import com.yahoo.document.annotation.*;
import com.yahoo.document.datatypes.*;
import com.yahoo.document.predicate.BinaryFormat;
import com.yahoo.document.update.*;
import com.yahoo.io.GrowableByteBuffer;
import com.yahoo.tensor.serialization.TypedBinaryFormat;
import com.yahoo.vespa.objects.BufferSerializer;
import com.yahoo.vespa.objects.FieldBase;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.logging.Logger;

import static com.yahoo.text.Utf8.calculateBytePositions;

/**
 * Class used for serializing documents on the Vespa 4.2 document format.
 *
 * @deprecated Please use {@link com.yahoo.document.serialization.VespaDocumentSerializerHead} instead for new code.
 * @author baldersheim
 */
@Deprecated // OK: Don't remove on Vespa 6: Mail may have documents on this format still
// When removing: Move content into VespaDocumentSerializerHead
public class VespaDocumentSerializer42 extends BufferSerializer implements DocumentSerializer {

    private final Compressor compressor = new Compressor();
    private final static Logger log = Logger.getLogger(VespaDocumentSerializer42.class.getName());
    private boolean headerOnly;
    private int spanNodeCounter = -1;
    private int[] bytePositions;

    VespaDocumentSerializer42(GrowableByteBuffer buf) {
        super(buf);
    }

    VespaDocumentSerializer42(ByteBuffer buf) {
        super(buf);
    }

    VespaDocumentSerializer42(byte[] buf) {
        super(buf);
    }

    VespaDocumentSerializer42() {
        super();
    }

    VespaDocumentSerializer42(GrowableByteBuffer buf, boolean headerOnly) {
        this(buf);
        this.headerOnly = headerOnly;
    }

    public void setHeaderOnly(boolean headerOnly) {
        this.headerOnly = headerOnly;
    }

    public void write(Document doc) {
        write(new Field(doc.getDataType().getName(), 0, doc.getDataType(), true), doc);
    }

    public void write(FieldBase field, Document doc) {
        //save the starting position in the buffer
        int startPos = buf.position();

        buf.putShort(Document.SERIALIZED_VERSION);

        // Temporary length, fill in after serialization is done.
        buf.putInt(0);

        doc.getId().serialize(this);

        byte contents = 0x01; // Indicating we have document type which we always have
        if (doc.getHeader().getFieldCount() > 0) {
            contents |= 0x2; // Indicate we have header
        }
        if (!headerOnly && doc.getBody().getFieldCount() > 0) {
            contents |= 0x4; // Indicate we have a body
        }
        buf.put(contents);

        doc.getDataType().serialize(this);

        if (doc.getHeader().getFieldCount() > 0) {
            doc.getHeader().serialize(doc.getDataType().getField("header"), this);
        }

        if (!headerOnly && doc.getBody().getFieldCount() > 0) {
            doc.getBody().serialize(doc.getDataType().getField("body"), this);
        }

        int finalPos = buf.position();

        buf.position(startPos + 2);
        buf.putInt(finalPos - startPos - 2 - 4); // Don't include the length itself or the version
        buf.position(finalPos);

    }

    /**
     * Write out the value of field
     *
     * @param field - field description (name and data type)
     * @param value - field value
     */
    public void write(FieldBase field, FieldValue value) {
        throw new IllegalArgumentException("Not Implemented");
    }

    /**
     * Write out the value of array field
     *
     * @param field - field description (name and data type)
     * @param array - field value
     */
    public <T extends FieldValue> void write(FieldBase field, Array<T> array) {
        buf.putInt1_2_4Bytes(array.size());

        List<T> lst = array.getValues();
        for (FieldValue value : lst) {
            value.serialize(this);
        }

    }

    public <K extends FieldValue, V extends FieldValue> void write(FieldBase field, MapFieldValue<K, V> map) {
        buf.putInt1_2_4Bytes(map.size());
        for (Map.Entry<K, V> e : map.entrySet()) {
            e.getKey().serialize(this);
            e.getValue().serialize(this);
        }
    }

    /**
     * Write out the value of byte field
     *
     * @param field - field description (name and data type)
     * @param value - field value
     */
    public void write(FieldBase field, ByteFieldValue value) {
        buf.put(value.getByte());
    }

    /**
     * Write out the value of collection field
     *
     * @param field - field description (name and data type)
     * @param value - field value
     */
    public <T extends FieldValue> void write(FieldBase field, CollectionFieldValue<T> value) {
        throw new IllegalArgumentException("Not Implemented");
    }

    /**
     * Write out the value of double field
     *
     * @param field - field description (name and data type)
     * @param value - field value
     */
    public void write(FieldBase field, DoubleFieldValue value) {
        buf.putDouble(value.getDouble());
    }

    /**
     * Write out the value of float field
     *
     * @param field - field description (name and data type)
     * @param value - field value
     */
    public void write(FieldBase field, FloatFieldValue value) {
        buf.putFloat(value.getFloat());
    }

    /**
     * Write out the value of integer field
     *
     * @param field - field description (name and data type)
     * @param value - field value
     */
    public void write(FieldBase field, IntegerFieldValue value) {
        buf.putInt(value.getInteger());
    }

    /**
     * Write out the value of long field
     *
     * @param field - field description (name and data type)
     * @param value - field value
     */
    public void write(FieldBase field, LongFieldValue value) {
        buf.putLong(value.getLong());
    }

    /**
     * Write out the value of raw field
     *
     * @param field - field description (name and data type)
     * @param value - field value
     */
    public void write(FieldBase field, Raw value) {
        ByteBuffer rawBuf = value.getByteBuffer();
        int origPos = rawBuf.position();
        buf.putInt(rawBuf.remaining());
        buf.put(rawBuf);
        rawBuf.position(origPos);

    }

    @Override
    public void write(FieldBase field, PredicateFieldValue value) {
        byte[] buf = BinaryFormat.encode(value.getPredicate());
        this.buf.putInt(buf.length);
        this.buf.put(buf);
    }

    /**
     * Write out the value of string field
     *
     * @param field - field description (name and data type)
     * @param value - field value
     */
    public void write(FieldBase field, StringFieldValue value) {
        byte[] stringBytes = createUTF8CharArray(value.getString());

        byte coding = 0;
        //Use bit 6 of "coding" to say whether span tree is available or not
        if (!value.getSpanTrees().isEmpty()) {
            coding |= 64;
        }
        buf.put(coding);
        buf.putInt1_4Bytes(stringBytes.length + 1);

        buf.put(stringBytes);
        buf.put(((byte) 0));

        Map<String, SpanTree> trees = value.getSpanTreeMap();
        if ((trees != null) && !trees.isEmpty()) {
            try {
                //we don't support serialization of nested span trees, so this is safe:
                bytePositions = calculateBytePositions(value.getString());
                //total length. record position and go back here if necessary:
                int posBeforeSize = buf.position();
                buf.putInt(0);
                buf.putInt1_2_4Bytes(trees.size());

                for (SpanTree tree : trees.values()) {
                    try {
                        write(tree);
                    } catch (SerializationException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        throw new SerializationException("Exception thrown while serializing span tree '" +
                                                         tree.getName() + "'; string='" + value.getString() + "'", e);
                    }
                }
                int endPos = buf.position();
                buf.position(posBeforeSize);
                buf.putInt(endPos - posBeforeSize - 4); //length shall exclude itself
                buf.position(endPos);
            } finally {
                bytePositions = null;
            }
        }
    }

    @Override
    public void write(FieldBase field, TensorFieldValue value) {
        if (value.getTensor().isPresent()) {
            byte[] encodedTensor = TypedBinaryFormat.encode(value.getTensor().get());
            buf.putInt1_4Bytes(encodedTensor.length);
            buf.put(encodedTensor);
        } else {
            buf.putInt1_4Bytes(0);
        }
    }

    /**
     * Write out the value of struct field
     *
     * @param field - field description (name and data type)
     * @param s     - field value
     */
    public void write(FieldBase field, Struct s) {
        // Serialize all parts first.. As we need to know length before starting
        // Serialize all the fields.

        //keep the buffer we're serializing everything into:
        GrowableByteBuffer bigBuffer = buf;

        //create a new buffer and serialize into that for a while:
        GrowableByteBuffer buffer = new GrowableByteBuffer(4096, 2.0f);
        buf = buffer;

        List<Integer> fieldIds = new LinkedList<>();
        List<java.lang.Integer> fieldLengths = new LinkedList<>();

        for (Map.Entry<Field, FieldValue> value : s.getFields()) {

            int startPos = buffer.position();
            value.getValue().serialize(value.getKey(), this);

            fieldLengths.add(buffer.position() - startPos);
            fieldIds.add(value.getKey().getId(s.getVersion()));
        }

        // Switch buffers again:
        buffer.flip();
        buf = bigBuffer;

        int uncompressedSize = buffer.remaining();
        Compressor.Compression compression =
            s.getDataType().getCompressor().compress(buffer.getByteBuffer().array(), buffer.remaining());

        // Actual serialization starts here.
        int lenPos = buf.position();
        putInt(null, 0); // Move back to this after compression is done.
        buf.put(compression.type().getCode());

        if (compression.data() != null && compression.type().isCompressed()) {
            buf.putInt2_4_8Bytes(uncompressedSize);
        }

        buf.putInt1_4Bytes(s.getFieldCount());

        for (int i = 0; i < s.getFieldCount(); ++i) {
            putInt1_4Bytes(null, fieldIds.get(i));
            putInt2_4_8Bytes(null, fieldLengths.get(i));
        }

        int pos = buf.position();
        if (compression.data() != null) {
            put(null, compression.data());
        } else {
            put(null, buffer.getByteBuffer());
        }
        int dataLength = buf.position() - pos;

        int posNow = buf.position();
        buf.position(lenPos);
        putInt(null, dataLength);
        buf.position(posNow);
    }

    /**
     * Write out the value of structured field
     *
     * @param field - field description (name and data type)
     * @param value - field value
     */
    public void write(FieldBase field, StructuredFieldValue value) {
        throw new IllegalArgumentException("Not Implemented");
    }

    /**
     * Write out the value of weighted set field
     *
     * @param field - field description (name and data type)
     * @param ws    - field value
     */
    public <T extends FieldValue> void write(FieldBase field, WeightedSet<T> ws) {
        WeightedSetDataType type = ws.getDataType();
        putInt(null, type.getNestedType().getId());
        putInt(null, ws.size());

        Iterator<T> it = ws.fieldValueIterator();
        while (it.hasNext()) {
            FieldValue key = it.next();
            java.lang.Integer value = ws.get(key);
            int sizePos = buf.position();
            putInt(null, 0);
            int startPos = buf.position();
            key.serialize(this);
            putInt(null, value);
            int finalPos = buf.position();
            int size = finalPos - startPos;
            buf.position(sizePos);
            putInt(null, size);
            buf.position(finalPos);
        }

    }

    public void write(FieldBase field, AnnotationReference value) {
        int annotationId = value.getReference().getScratchId();
        if (annotationId >= 0) {
            buf.putInt1_2_4Bytes(annotationId);
        } else {
            throw new SerializationException("Could not serialize AnnotationReference value, reference not found (" + value + ")");
        }
    }

    public void write(DocumentId id) {
        put(null, id.getScheme().toUtf8().getBytes());
        putByte(null, (byte) 0);
    }

    public void write(DocumentType type) {
        byte[] docType = createUTF8CharArray(type.getName());
        put(null, docType);
        putByte(null, ((byte) 0));
        putShort(null, (short) 0); // Used to hold the version. Is now always 0.
    }


    private static void serializeAttributeString(GrowableByteBuffer data, String input) {
        byte[] inputBytes = createUTF8CharArray(input);
        data.put((byte) (inputBytes.length));
        data.put(inputBytes);
        data.put((byte) 0);
    }

    public void write(Annotation annotation) {
        buf.putInt(annotation.getType().getId());  //name hash

        byte features = 0;
        if (annotation.isSpanNodeValid()) {
            features |= ((byte) 1);
        }
        if (annotation.hasFieldValue()) {
            features |= ((byte) 2);
        }
        buf.put(features);

        int posBeforeSize = buf.position();
        buf.putInt1_2_4BytesAs4(0);

        //write ID of span node:
        if (annotation.isSpanNodeValid()) {
            int spanNodeId = annotation.getSpanNode().getScratchId();
            if (spanNodeId >= 0) {
                buf.putInt1_2_4Bytes(spanNodeId);
            } else {
                throw new SerializationException("Could not serialize annotation, associated SpanNode not found (" + annotation + ")");
            }
        }

        //write annotation value:
        if (annotation.hasFieldValue()) {
            buf.putInt(annotation.getType().getDataType().getId());
            annotation.getFieldValue().serialize(this);
        }

        int end = buf.position();
        buf.position(posBeforeSize);
        buf.putInt1_2_4BytesAs4(end - posBeforeSize - 4);
        buf.position(end);
    }

    public void write(SpanTree tree) {
        //we don't support serialization of nested span trees:
        if (spanNodeCounter >= 0) {
            throw new SerializationException("Serialization of nested SpanTrees is not supported.");
        }

        //we're going to write a new SpanTree, create a new Map for nodes:
        spanNodeCounter = 0;

        //make sure tree is consistent before continuing:
        tree.cleanup();

        try {
            new StringFieldValue(tree.getName()).serialize(this);

            write(tree.getRoot());
            {
                //add all annotations to temporary list and sort it, to get predictable serialization
                List<Annotation> tmpAnnotationList = new ArrayList<Annotation>(tree.numAnnotations());
                for (Annotation annotation : tree) {
                    tmpAnnotationList.add(annotation);
                }
                Collections.sort(tmpAnnotationList);

                int annotationCounter = 0;
                //add all annotations to map here, in case of back-references:
                for (Annotation annotation : tmpAnnotationList) {
                    annotation.setScratchId(annotationCounter++);
                }

                buf.putInt1_2_4Bytes(tmpAnnotationList.size());
                for (Annotation annotation : tmpAnnotationList) {
                    write(annotation);
                }
            }
        } finally {
            //we're done, let's set these to null to save memory and prevent madness:
            spanNodeCounter = -1;
        }
    }

    public void write(SpanNode spanNode) {
        if (spanNodeCounter >= 0) {
            spanNode.setScratchId(spanNodeCounter++);
        }
        if (spanNode instanceof Span) {
            write((Span) spanNode);
        } else if (spanNode instanceof AlternateSpanList) {
            write((AlternateSpanList) spanNode);
        } else if (spanNode instanceof SpanList) {
            write((SpanList) spanNode);
        } else {
            throw new IllegalStateException("BUG!! Unable to serialize " + spanNode);
        }
    }

    public void write(Span span) {
        buf.put(Span.ID);

        if (bytePositions != null) {
            int byteFrom = bytePositions[span.getFrom()];
            int byteLength = bytePositions[span.getFrom() + span.getLength()] - byteFrom;

            buf.putInt1_2_4Bytes(byteFrom);
            buf.putInt1_2_4Bytes(byteLength);
        } else {
            throw new SerializationException("Cannot serialize Span " + span + ", no access to parent StringFieldValue.");
        }
    }

    public void write(SpanList spanList) {
        buf.put(SpanList.ID);
        buf.putInt1_2_4Bytes(spanList.numChildren());
        Iterator<SpanNode> children = spanList.childIterator();
        while (children.hasNext()) {
            write(children.next());
        }
    }

    public void write(AlternateSpanList altSpanList) {
        buf.put(AlternateSpanList.ID);
        buf.putInt1_2_4Bytes(altSpanList.getNumSubTrees());
        for (int i = 0; i < altSpanList.getNumSubTrees(); i++) {
            buf.putDouble(altSpanList.getProbability(i));
            buf.putInt1_2_4Bytes(altSpanList.numChildren(i));
            Iterator<SpanNode> children = altSpanList.childIterator(i);
            while (children.hasNext()) {
                write(children.next());
            }
        }
    }

    @Override
    public void write(DocumentUpdate update) {
        putShort(null, Document.SERIALIZED_VERSION);
        update.getId().serialize(this);

        byte contents = 0x1; // Legacy to say we have document type
        putByte(null, contents);
        update.getDocumentType().serialize(this);

        putInt(null, update.getFieldUpdates().size());

        for (FieldUpdate up : update.getFieldUpdates()) {
            up.serialize(this);
        }
    }

    @Override
    public void write(FieldUpdate update) {
        putInt(null, update.getField().getId(Document.SERIALIZED_VERSION));
        putInt(null, update.getValueUpdates().size());
        for (ValueUpdate vupd : update.getValueUpdates()) {
            putInt(null, vupd.getValueUpdateClassID().id);
            vupd.serialize(this, update.getField().getDataType());
        }
    }

    @Override
    public void write(AddValueUpdate update, DataType superType) {
        writeValue(this, ((CollectionDataType)superType).getNestedType(), update.getValue());
        putInt(null, update.getWeight());
    }

    @Override
    public void write(MapValueUpdate update, DataType superType) {
        if (superType instanceof ArrayDataType) {
            CollectionDataType type = (CollectionDataType) superType;
            IntegerFieldValue index = (IntegerFieldValue) update.getValue();
            index.serialize(this);
            putInt(null, update.getUpdate().getValueUpdateClassID().id);
            update.getUpdate().serialize(this, type.getNestedType());
        } else if (superType instanceof WeightedSetDataType) {
            writeValue(this, ((CollectionDataType)superType).getNestedType(), update.getValue());
            putInt(null, update.getUpdate().getValueUpdateClassID().id);
            update.getUpdate().serialize(this, DataType.INT);
        } else {
            throw new SerializationException("MapValueUpdate only works for arrays and weighted sets");
        }
    }

    @Override
    public void write(ArithmeticValueUpdate update) {
        putInt(null, update.getOperator().id);
        putDouble(null, update.getOperand().doubleValue());
    }

    @Override
    public void write(AssignValueUpdate update, DataType superType) {
        if (update.getValue() == null) {
            putByte(null, (byte) 0);
        } else {
            putByte(null, (byte) 1);
            writeValue(this, superType, update.getValue());
        }
    }

    @Override
    public void write(RemoveValueUpdate update, DataType superType) {
        writeValue(this, ((CollectionDataType)superType).getNestedType(), update.getValue());
    }

    @Override
    public void write(ClearValueUpdate clearValueUpdate, DataType superType) {
        //TODO: This has never ever been implemented. Has this ever worked?
    }

    /**
     * Returns the serialized size of the given {@link Document}. Please note that this method performs actual
     * serialization of the document, but simply return the size of the final {@link GrowableByteBuffer}. If you need
     * the buffer itself, do NOT use this method.
     *
     * @param doc The Document whose size to calculate.
     * @return The size in bytes.
     */
    public static long getSerializedSize(Document doc) {
        DocumentSerializer serializer = new VespaDocumentSerializerHead(new GrowableByteBuffer());
        serializer.write(doc);
        return serializer.getBuf().position();
    }

    private static void writeValue(VespaDocumentSerializer42 serializer, DataType dataType, Object value) {
        FieldValue fieldValue;
        if (value instanceof FieldValue) {
            fieldValue = (FieldValue)value;
        } else {
            fieldValue = dataType.createFieldValue(value);
        }
        fieldValue.serialize(serializer);
    }

}
