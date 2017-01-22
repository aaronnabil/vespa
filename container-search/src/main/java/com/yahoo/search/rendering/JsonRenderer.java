// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.rendering;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

import com.yahoo.document.datatypes.TensorFieldValue;
import com.yahoo.tensor.Tensor;
import org.json.JSONArray;
import org.json.JSONObject;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.yahoo.data.JsonProducer;
import com.yahoo.data.access.Inspectable;
import com.yahoo.data.access.simple.JsonRender;
import com.yahoo.document.datatypes.FieldValue;
import com.yahoo.document.datatypes.StringFieldValue;
import com.yahoo.document.json.JsonWriter;
import com.yahoo.prelude.fastsearch.FastHit;
import com.yahoo.processing.Response;
import com.yahoo.processing.execution.Execution.Trace;
import com.yahoo.processing.rendering.AsynchronousSectionedRenderer;
import com.yahoo.processing.request.CompoundName;
import com.yahoo.processing.response.Data;
import com.yahoo.processing.response.DataList;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.grouping.Continuation;
import com.yahoo.search.grouping.result.AbstractList;
import com.yahoo.search.grouping.result.BucketGroupId;
import com.yahoo.search.grouping.result.Group;
import com.yahoo.search.grouping.result.GroupId;
import com.yahoo.search.grouping.result.RawBucketId;
import com.yahoo.search.grouping.result.RawId;
import com.yahoo.search.grouping.result.RootGroup;
import com.yahoo.search.grouping.result.ValueGroupId;
import com.yahoo.search.result.Coverage;
import com.yahoo.search.result.DefaultErrorHit;
import com.yahoo.search.result.ErrorHit;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.search.result.Hit;
import com.yahoo.search.result.HitGroup;
import com.yahoo.search.result.NanNumber;
import com.yahoo.yolean.trace.TraceNode;
import com.yahoo.yolean.trace.TraceVisitor;

/**
 * JSON renderer for search results.
 *
 * @author Steinar Knutsen
 */
// NOTE: The JSON format is a public API. If new elements are added be sure to update the reference doc.
public class JsonRenderer extends AsynchronousSectionedRenderer<Result> {

    private static final CompoundName DEBUG_RENDERING_KEY = new CompoundName("renderer.json.debug");
    private static final CompoundName JSON_CALLBACK = new CompoundName("jsoncallback");

    private enum RenderDecision {
        YES, NO, DO_NOT_KNOW;

        boolean booleanValue() {
            switch (this) {
                case YES: return true;
                case NO: return false;
                default: throw new IllegalStateException();
            }
        }
    }

    // if this must be optimized, simply use com.fasterxml.jackson.core.SerializableString
    private static final String BUCKET_LIMITS = "limits";
    private static final String BUCKET_TO = "to";
    private static final String BUCKET_FROM = "from";
    private static final String CHILDREN = "children";
    private static final String CONTINUATION = "continuation";
    private static final String COVERAGE = "coverage";
    private static final String COVERAGE_COVERAGE = "coverage";
    private static final String COVERAGE_DOCUMENTS = "documents";
    private static final String COVERAGE_FULL = "full";
    private static final String COVERAGE_NODES = "nodes";
    private static final String COVERAGE_RESULTS = "results";
    private static final String COVERAGE_RESULTS_FULL = "resultsFull";
    private static final String ERRORS = "errors";
    private static final String ERROR_CODE = "code";
    private static final String ERROR_MESSAGE = "message";
    private static final String ERROR_SOURCE = "source";
    private static final String ERROR_STACK_TRACE = "stackTrace";
    private static final String ERROR_SUMMARY = "summary";
    private static final String FIELDS = "fields";
    private static final String ID = "id";
    private static final String LABEL = "label";
    private static final String RELEVANCE = "relevance";
    private static final String ROOT = "root";
    private static final String SOURCE = "source";
    private static final String TOTAL_COUNT = "totalCount";
    private static final String TRACE = "trace";
    private static final String TRACE_CHILDREN = "children";
    private static final String TRACE_MESSAGE = "message";
    private static final String TRACE_TIMESTAMP = "timestamp";
    private static final String TIMING = "timing";
    private static final String QUERY_TIME = "querytime";
    private static final String SUMMARY_FETCH_TIME = "summaryfetchtime";
    private static final String SEARCH_TIME = "searchtime";
    private static final String TYPES = "types";
    private static final String GROUPING_VALUE = "value";
    private static final String VESPA_HIDDEN_FIELD_PREFIX = "$";

    private final JsonFactory generatorFactory;

    private JsonGenerator generator;
    private Deque<Integer> renderedChildren;
    private boolean debugRendering;
    private LongSupplier timeSource;
    private OutputStream stream;

    private class TraceRenderer extends TraceVisitor {
        private final long basetime;
        private boolean hasFieldName = false;
        int emittedChildNesting = 0;
        int currentChildNesting = 0;
        private boolean insideOpenObject = false;

        TraceRenderer(long basetime) {
            this.basetime = basetime;
        }

        @Override
        public void entering(TraceNode node) {
            ++currentChildNesting;
        }

        @Override
        public void leaving(TraceNode node) {
            conditionalEndObject();
            if (currentChildNesting == emittedChildNesting) {
                try {
                    generator.writeEndArray();
                    generator.writeEndObject();
                } catch (IOException e) {
                    throw new TraceRenderWrapper(e);
                }
                --emittedChildNesting;
            }
            --currentChildNesting;
        }

        @Override
        public void visit(TraceNode node) {
            try {
                doVisit(node.timestamp(), node.payload(), node.children().iterator().hasNext());
            } catch (IOException e) {
                throw new TraceRenderWrapper(e);
            }
        }

        private void doVisit(final long timestamp, final Object payload, final boolean hasChildren) throws IOException {
            boolean dirty = false;
            if (timestamp != 0L) {
                header();
                generator.writeStartObject();
                generator.writeNumberField(TRACE_TIMESTAMP, timestamp - basetime);
                dirty = true;
            }
            if (payload != null) {
                if (!dirty) {
                    header();
                    generator.writeStartObject();
                }
                generator.writeStringField(TRACE_MESSAGE, payload.toString());
                dirty = true;
            }
            if (dirty) {
                if (!hasChildren) {
                    generator.writeEndObject();
                } else {
                    setInsideOpenObject(true);
                }
            }
        }

        private void header() {
            fieldName();
            for (int i = 0; i < (currentChildNesting - emittedChildNesting); ++i) {
                startChildArray();
            }
            emittedChildNesting = currentChildNesting;
        }

        private void startChildArray() {
            try {
                conditionalStartObject();
                generator.writeArrayFieldStart(TRACE_CHILDREN);
            } catch (IOException e) {
                throw new TraceRenderWrapper(e);
            }
        }

        private void conditionalStartObject() throws IOException {
            if (!isInsideOpenObject()) {
                generator.writeStartObject();
            } else {
                setInsideOpenObject(false);
            }
        }

        private void conditionalEndObject() {
            if (isInsideOpenObject()) {
                // This triggers if we were inside a data node with payload and
                // subnodes, but none of the subnodes contained data
                try {
                    generator.writeEndObject();
                    setInsideOpenObject(false);
                } catch (IOException e) {
                    throw new TraceRenderWrapper(e);
                }
            }
        }

        private void fieldName() {
            if (hasFieldName) {
                return;
            }

            try {
                generator.writeFieldName(TRACE);
            } catch (IOException e) {
                throw new TraceRenderWrapper(e);
            }
            hasFieldName = true;
        }

        boolean isInsideOpenObject() {
            return insideOpenObject;
        }

        void setInsideOpenObject(boolean insideOpenObject) {
            this.insideOpenObject = insideOpenObject;
        }
    }

    private static final class TraceRenderWrapper extends RuntimeException {

        /**
         * Should never be serialized, but this is still needed.
         */
        private static final long serialVersionUID = 2L;

        TraceRenderWrapper(IOException wrapped) {
            super(wrapped);
        }

    }

    public JsonRenderer() {
        this(null);
    }

    /** 
     * Creates a json renderer using a custom executor.
     * Using a custom executor is useful for tests to avoid creating new threads for each renderer registry.
     */
    public JsonRenderer(Executor executor) {
        super(executor);
        generatorFactory = new JsonFactory();
        generatorFactory.setCodec(createJsonCodec());
    }

    /**
     * Create the codec used for rendering instances of {@link TreeNode}. This
     * method will be invoked when creating the first renderer instance, but not
     * for each fresh clone used by individual results.
     *
     * @return an object mapper for the internal JsonFactory
     */
    protected static ObjectMapper createJsonCodec() {
        return new ObjectMapper();
    }

    @Override
    public void init() {
        super.init();
        generator = null;
        renderedChildren = null;
        debugRendering = false;
        timeSource = System::currentTimeMillis;
        stream = null;
    }

    @Override
    public void beginResponse(OutputStream stream) throws IOException {
        beginJsonCallback(stream);
        generator = generatorFactory.createGenerator(stream, JsonEncoding.UTF8);
        renderedChildren = new ArrayDeque<>();
        debugRendering = getDebugRendering(getResult().getQuery());
        generator.writeStartObject();
        renderTrace(getExecution().trace());
        renderTiming();
        generator.writeFieldName(ROOT);
    }

    private void renderTiming() throws IOException {
        if (!getResult().getQuery().getPresentation().getTiming()) return;

        double milli = .001d;
        long now = timeSource.getAsLong();
        long searchTime = now - getResult().getElapsedTime().first();
        double searchSeconds = searchTime * milli;

        generator.writeObjectFieldStart(TIMING);
        if (getResult().getElapsedTime().firstFill() != 0L) {
            long queryTime = getResult().getElapsedTime().weightedSearchTime();
            long summaryFetchTime = getResult().getElapsedTime().weightedFillTime();
            double querySeconds = queryTime * milli;
            double summarySeconds = summaryFetchTime * milli;
            generator.writeNumberField(QUERY_TIME, querySeconds);
            generator.writeNumberField(SUMMARY_FETCH_TIME, summarySeconds);
        }

        generator.writeNumberField(SEARCH_TIME, searchSeconds);
        generator.writeEndObject();
    }

    private boolean getDebugRendering(Query q) {
        return q != null && q.properties().getBoolean(DEBUG_RENDERING_KEY, false);
    }

    private void renderTrace(Trace trace) throws IOException {
        if (!trace.traceNode().children().iterator().hasNext()) return;

        try {
            long basetime = trace.traceNode().timestamp();
            if (basetime == 0L)
                basetime = getResult().getElapsedTime().first();
            trace.accept(new TraceRenderer(basetime));
        } catch (TraceRenderWrapper e) {
            throw new IOException(e);
        }
    }

    @Override
    public void beginList(DataList<?> list) throws IOException {
        Preconditions.checkArgument(list instanceof HitGroup,
                                    "Expected subclass of com.yahoo.search.result.HitGroup, got %s.",
                                    list.getClass());
        moreChildren();
        renderHitGroupHead((HitGroup) list);
    }

    protected void moreChildren() throws IOException {
        if (!renderedChildren.isEmpty())
            childrenArray();

        renderedChildren.push(0);
    }

    private void childrenArray() throws IOException {
        if (renderedChildren.peek() == 0)
            generator.writeArrayFieldStart(CHILDREN);
        renderedChildren.push(renderedChildren.pop() + 1);
    }

    private void lessChildren() throws IOException {
        int lastRenderedChildren = renderedChildren.pop();
        if (lastRenderedChildren > 0) {
            generator.writeEndArray();
        }
    }

    private void renderHitGroupHead(HitGroup hitGroup) throws IOException {
        generator.writeStartObject();

        renderHitContents(hitGroup);
        if (getRecursionLevel() == 1)
            renderCoverage();

        ErrorHit errorHit = hitGroup.getErrorHit();
        if (errorHit != null)
            renderErrors(errorHit.errors());

        // the framework will invoke begin methods as needed from here
    }

    private void renderErrors(Set<ErrorMessage> errors) throws IOException {
        if (errors.isEmpty()) return;

        generator.writeArrayFieldStart(ERRORS);
        for (ErrorMessage e : errors) {
            String summary = e.getMessage();
            String source = e.getSource();
            Throwable cause = e.getCause();
            String message = e.getDetailedMessage();
            generator.writeStartObject();
            generator.writeNumberField(ERROR_CODE, e.getCode());
            generator.writeStringField(ERROR_SUMMARY, summary);
            if (source != null) {
                generator.writeStringField(ERROR_SOURCE, source);
            }
            if (message != null) {
                generator.writeStringField(ERROR_MESSAGE, message);
            }
            if (cause != null && cause.getStackTrace().length > 0) {
                StringWriter s = new StringWriter();
                PrintWriter p = new PrintWriter(s);
                cause.printStackTrace(p);
                p.close();
                generator.writeStringField(ERROR_STACK_TRACE, s.toString());
            }
            generator.writeEndObject();
        }
        generator.writeEndArray();


    }

    private void renderCoverage() throws IOException {
        Coverage c = getResult().getCoverage(false);
        if (c == null) return;

        generator.writeObjectFieldStart(COVERAGE);
        generator.writeNumberField(COVERAGE_COVERAGE, c.getResultPercentage());
        generator.writeNumberField(COVERAGE_DOCUMENTS, c.getDocs());
        generator.writeBooleanField(COVERAGE_FULL, c.getFull());
        generator.writeNumberField(COVERAGE_NODES, c.getNodes());
        generator.writeNumberField(COVERAGE_RESULTS, c.getResultSets());
        generator.writeNumberField(COVERAGE_RESULTS_FULL, c.getFullResultSets());
        generator.writeEndObject();
    }

    private void renderHit(Hit hit) throws IOException {
        if (!shouldRender(hit)) return;

        childrenArray();
        generator.writeStartObject();
        renderHitContents(hit);
        generator.writeEndObject();
    }

    private boolean shouldRender(Hit hit) {
        return ! (hit instanceof DefaultErrorHit);
    }

    private boolean fieldsStart(boolean hasFieldsField) throws IOException {
        if (hasFieldsField) return true;
        generator.writeObjectFieldStart(FIELDS);
        return true;
    }

    private void fieldsEnd(boolean hasFieldsField) throws IOException {
        if (!hasFieldsField) return;
        generator.writeEndObject();
    }

    private void renderHitContents(Hit hit) throws IOException {
        String id = hit.getDisplayId();
        if (id != null)
            generator.writeStringField(ID, id);

        generator.writeNumberField(RELEVANCE, hit.getRelevance().getScore());

        if (hit.types().size() > 0) { // TODO: Remove types rendering on Vespa 7
            generator.writeArrayFieldStart(TYPES);
            for (String t : hit.types()) {
                generator.writeString(t);
            }
            generator.writeEndArray();
        }

        String source = hit.getSource();
        if (source != null)
            generator.writeStringField(SOURCE, hit.getSource());

        renderSpecialCasesForGrouping(hit);

        renderAllFields(hit);
    }

    private void renderAllFields(Hit hit) throws IOException {
        boolean hasFieldsField = false;

        hasFieldsField |= renderTotalHitCount(hit, hasFieldsField);
        hasFieldsField |= renderStandardFields(hit, hasFieldsField);
        fieldsEnd(hasFieldsField);
    }

    private boolean renderStandardFields(Hit hit, boolean initialHasFieldsField) throws IOException {
        boolean hasFieldsField = initialHasFieldsField;
        for (String fieldName : hit.fieldKeys()) {
            if (!shouldRender(fieldName, hit)) continue;

            // We can't look at the size of fieldKeys() and know whether we need
            // the fields object, as all fields may be hidden.
            hasFieldsField |= fieldsStart(hasFieldsField);
            renderField(fieldName, hit);
        }
        return hasFieldsField;
    }

    private boolean shouldRender(String fieldName, Hit hit) {
        if (debugRendering) return true;

        if (fieldName.startsWith(VESPA_HIDDEN_FIELD_PREFIX)) return false;

        RenderDecision r = lazyRenderAwareCheck(fieldName, hit);
        if (r != RenderDecision.DO_NOT_KNOW) return r.booleanValue();

        // this will trigger field decoding, so it is important the lazy decoding magic is done first
        Object field = hit.getField(fieldName);

        if (field instanceof CharSequence && ((CharSequence) field).length() == 0) return false;

        // StringFieldValue cannot hold a null, so checking length directly is OK:
        if (field instanceof StringFieldValue && ((StringFieldValue) field).getString().isEmpty()) return false;

        if (field instanceof NanNumber) return false;

        return true;
    }

    private RenderDecision lazyRenderAwareCheck(String fieldName, Hit hit) {
        if ( ! (hit instanceof FastHit)) return RenderDecision.DO_NOT_KNOW;

        FastHit asFastHit = (FastHit) hit;
        if (asFastHit.fieldIsNotDecoded(fieldName)) {
            FastHit.RawField rawField = asFastHit.fetchFieldAsUtf8(fieldName);
            if (rawField != null)
                return rawField.getUtf8().length == 0 ? RenderDecision.NO : RenderDecision.YES;
        }
        return RenderDecision.DO_NOT_KNOW;
    }

    private void renderSpecialCasesForGrouping(Hit hit) throws IOException {
        if (hit instanceof AbstractList) {
            renderGroupingListSyntheticFields((AbstractList) hit);
        } else if (hit instanceof Group) {
            renderGroupingGroupSyntheticFields(hit);
        }
    }

    private void renderGroupingGroupSyntheticFields(Hit hit) throws IOException {
        renderGroupMetadata(((Group) hit).getGroupId());
        if (hit instanceof RootGroup) {
            renderContinuations(Collections.singletonMap(
                    Continuation.THIS_PAGE, ((RootGroup) hit).continuation()));
        }
    }

    private void renderGroupingListSyntheticFields(AbstractList a) throws IOException {
        writeGroupingLabel(a);
        renderContinuations(a.continuations());
    }

    private void writeGroupingLabel(AbstractList a) throws IOException {
        generator.writeStringField(LABEL, a.getLabel());
    }

    private void renderContinuations(Map<String, Continuation> continuations) throws IOException {
        if (continuations.isEmpty()) return;

        generator.writeObjectFieldStart(CONTINUATION);
        for (Map.Entry<String, Continuation> e : continuations.entrySet()) {
            generator.writeStringField(e.getKey(), e.getValue().toString());
        }
        generator.writeEndObject();
    }

    private void renderGroupMetadata(GroupId id) throws IOException {
        if (!(id instanceof ValueGroupId || id instanceof BucketGroupId)) return;

        if (id instanceof ValueGroupId) {
            ValueGroupId<?> valueId = (ValueGroupId<?>) id;
            generator.writeStringField(GROUPING_VALUE, getIdValue(valueId));
        } else {
            BucketGroupId<?> bucketId = (BucketGroupId<?>) id;
            generator.writeObjectFieldStart(BUCKET_LIMITS);
            generator.writeStringField(BUCKET_FROM, getBucketFrom(bucketId));
            generator.writeStringField(BUCKET_TO, getBucketTo(bucketId));
            generator.writeEndObject();
        }
    }

    private static String getIdValue(ValueGroupId<?> id) {
        return (id instanceof RawId ? Arrays.toString(((RawId) id).getValue()) : id.getValue()).toString();
    }

    private static String getBucketFrom(BucketGroupId<?> id) {
        return (id instanceof RawBucketId ? Arrays.toString(((RawBucketId) id).getFrom()) : id.getFrom()).toString();
    }

    private static String getBucketTo(BucketGroupId<?> id) {
        return (id instanceof RawBucketId ? Arrays.toString(((RawBucketId) id).getTo()) : id.getTo()).toString();
    }

    private boolean renderTotalHitCount(Hit hit, boolean hasFieldsField) throws IOException {
        if ( ! (getRecursionLevel() == 1 && hit instanceof HitGroup)) return false;

        fieldsStart(hasFieldsField);
        generator.writeNumberField(TOTAL_COUNT, getResult().getTotalHitCount());
        return true;
    }

    private void renderField(String fieldName, Hit hit) throws IOException {
        generator.writeFieldName(fieldName);
        if ( ! tryDirectRendering(fieldName, hit)) {
            renderFieldContents(hit.getField(fieldName));
        }
    }

    private void renderFieldContents(Object field) throws IOException {
        if (field == null) {
            generator.writeNull();
        } else if (field instanceof Number) {
            renderNumberField((Number) field);
        } else if (field instanceof TreeNode) {
            generator.writeTree((TreeNode) field);
        } else if (field instanceof Tensor) {
            renderTensor(Optional.of((Tensor)field));
        } else if (field instanceof JsonProducer) {
            generator.writeRawValue(((JsonProducer) field).toJson());
        } else if (field instanceof Inspectable) {
            StringBuilder intermediate = new StringBuilder();
            JsonRender.render((Inspectable) field, intermediate, true);
            generator.writeRawValue(intermediate.toString());
        } else if (field instanceof StringFieldValue) {
            // This needs special casing as JsonWriter hides empty strings now
            generator.writeString(((StringFieldValue)field).getString());
        } else if (field instanceof TensorFieldValue) {
            renderTensor(((TensorFieldValue)field).getTensor());
        } else if (field instanceof FieldValue) {
            // the null below is the field which has already been written
            ((FieldValue) field).serialize(null, new JsonWriter(generator));
        } else if (field instanceof JSONArray || field instanceof JSONObject) {
            // org.json returns null if the object would not result in
            // syntactically correct JSON
            String s = field.toString();
            if (s == null) {
                generator.writeNull();
            } else {
                generator.writeRawValue(s);
            }
        } else {
            generator.writeString(field.toString());
        }
    }

    private void renderNumberField(Number field) throws IOException {
        if (field instanceof Integer) {
            generator.writeNumber(field.intValue());
        }  else if (field instanceof Float) {
            generator.writeNumber(field.floatValue());
        }  else if (field instanceof Double) {
            generator.writeNumber(field.doubleValue());
        } else if (field instanceof Long) {
            generator.writeNumber(field.longValue());
        } else if (field instanceof Byte || field instanceof Short) {
            generator.writeNumber(field.intValue());
        } else if (field instanceof BigInteger) {
            generator.writeNumber((BigInteger) field);
        } else if (field instanceof BigDecimal) {
            generator.writeNumber((BigDecimal) field);
        } else {
            generator.writeNumber(field.doubleValue());
        }
    }

    private void renderTensor(Optional<Tensor> tensor) throws IOException {
        if ( ! tensor.isPresent()) return;

        generator.writeStartObject();
        generator.writeArrayFieldStart("cells");
        for (Iterator<Tensor.Cell> i = tensor.get().cellIterator(); i.hasNext(); ) {
            Tensor.Cell cell = i.next();
            
            generator.writeStartObject();

            generator.writeObjectFieldStart("address");
            for (int d = 0; d < cell.getKey().size(); d++)
                generator.writeObjectField(tensor.get().type().dimensions().get(d).name(), cell.getKey().label(d));            
            generator.writeEndObject();

            generator.writeObjectField("value", cell.getValue());

            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    /**
     * Really a private method, but package access for testability.
     */
    boolean tryDirectRendering(String fieldName, Hit hit) throws IOException {
        boolean renderedAsUtf8 = false;
        if (hit instanceof FastHit) {
            FastHit f = (FastHit) hit;
            if (f.fieldIsNotDecoded(fieldName)) {
                FastHit.RawField r = f.fetchFieldAsUtf8(fieldName);
                if (r != null) {
                    byte[] utf8 = r.getUtf8();

                    generator.writeUTF8String(utf8, 0, utf8.length);
                    renderedAsUtf8 = true;
                }
            }
        }
        return renderedAsUtf8;
    }

    @Override
    public void data(Data data) throws IOException {
        Preconditions.checkArgument(data instanceof Hit,
                                    "Expected subclass of com.yahoo.search.result.Hit, got %s.",
                                    data.getClass());
        renderHit((Hit) data);
    }

    @Override
    public void endList(DataList<?> list) throws IOException {
        lessChildren();
        generator.writeEndObject();
    }

    @Override
    public void endResponse() throws IOException {
        generator.close();
        endJsonCallback();
    }

    @Override
    public String getEncoding() {
        return "utf-8";
    }

    @Override
    public String getMimeType() {
        return "application/json";
    }

    private Result getResult() {
        Response r = getResponse();
        Preconditions.checkArgument(r instanceof Result,
                                    "JsonRenderer can only render instances of com.yahoo.search.Result, got instance of %s.",
                                    r.getClass());
        return (Result) r;
    }

    /**
     * Adds JSONP (Json with padding) support.
     *
     * Basically, if the JSON renderer receives a query parameter "jsoncallback=...",
     * the JSON response will be wrapped in a function call with the name specified
     * by the client. This side-steps the same-origin policy, thus supports calling
     * Vespa from javascript loaded from a different domain then the Vespa instance.
     */
    private void beginJsonCallback(OutputStream stream) throws IOException {
        if (shouldRenderJsonCallback()) {
            String jsonCallback = getJsonCallback() + "(";
            stream.write(jsonCallback.getBytes(StandardCharsets.UTF_8));
            this.stream = stream;
        }
    }

    private void endJsonCallback() throws IOException {
        if (shouldRenderJsonCallback() && stream != null) {
            stream.write(");".getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean shouldRenderJsonCallback() {
        String jsonCallback = getJsonCallback();
        return jsonCallback != null && !"".equals(jsonCallback);
    }

    private String getJsonCallback() {
        Result result = getResult();
        if (result != null) {
            Query query = result.getQuery();
            if (query != null) {
                return query.properties().getString(JSON_CALLBACK, null);
            }
        }
        return null;
    }

    /**
     * Only for testing. Never to be used in any other context.
     */
    void setGenerator(JsonGenerator generator) {
        this.generator = generator;
    }

    /**
     * Only for testing. Never to be used in any other context.
     */
    void setTimeSource(LongSupplier timeSource) {
        this.timeSource = timeSource;
    }
    
}
