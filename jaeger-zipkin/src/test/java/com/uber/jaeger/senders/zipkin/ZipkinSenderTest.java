/*
 * Copyright (c) 2016, Uber Technologies, Inc
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.jaeger.senders.zipkin;

import static org.junit.Assert.assertEquals;

import com.uber.jaeger.Span;
import com.uber.jaeger.SpanContext;
import com.uber.jaeger.Tracer;
import com.uber.jaeger.exceptions.SenderException;
import com.uber.jaeger.metrics.InMemoryStatsReporter;
import com.uber.jaeger.reporters.InMemoryReporter;
import com.uber.jaeger.reporters.Reporter;
import com.uber.jaeger.samplers.ConstSampler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.thrift.transport.AutoExpandingBufferWriteTransport;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import zipkin.Annotation;
import zipkin.BinaryAnnotation;
import zipkin.junit.ZipkinRule;
import zipkin.reporter.urlconnection.URLConnectionSender;

public class ZipkinSenderTest {
  final int messageMaxBytes = 1000;

  @Rule public ZipkinRule zipkinRule = new ZipkinRule();

  ZipkinSender sender;
  Reporter reporter;
  ThriftSpanConverter converter;
  Tracer tracer;

  @Before
  public void setUp() throws Exception {
    reporter = new InMemoryReporter();
    tracer =
        new Tracer.Builder("test-sender", reporter, new ConstSampler(true))
            .withStatsReporter(new InMemoryStatsReporter())
            .build();
    sender = newSender(messageMaxBytes);
    converter = new ThriftSpanConverter();
  }

  @After
  public void tearDown() throws Exception {
    sender.close();
    reporter.close();
  }

  @Test
  public void testAppendSpanTooLarge() throws Exception {
    Span jaegerSpan = (Span) tracer.buildSpan("raza").startManual();
    String msg = "";
    for (int i = 0; i < 1001; i++) {
      msg += ".";
    }

    jaegerSpan.log(msg, new Object());
    try {
      sender.append(jaegerSpan);
    } catch (SenderException e) {
      assertEquals(e.getDroppedSpanCount(), 1);
    }
  }

  @Test
  public void testAppend() throws Exception {
    // find size of the initial span
    AutoExpandingBufferWriteTransport memoryTransport =
        new AutoExpandingBufferWriteTransport(messageMaxBytes, 2);
    Span jaegerSpan = (Span) tracer.buildSpan("raza").startManual();
    com.twitter.zipkin.thriftjava.Span span = ThriftSpanConverter.convertSpan(jaegerSpan);

    int expectedNumSpans = 11;
    List<byte[]> spansToSend = new ArrayList(expectedNumSpans);
    for (int i = 0; i < expectedNumSpans; i++) {
      spansToSend.add(new ThriftSpanEncoder().encode(sender.backFillHostOnAnnotations(span)));
    }

    // create a sender thats a multiple of the span size (accounting for span overhead)
    // this allows us to test the boundary conditions of writing spans.
    int messageMaxBytes = sender.delegate.messageSizeInBytes(spansToSend);
    sender.close();
    sender = newSender(messageMaxBytes);

    // add enough spans to be under buffer limit
    for (int i = 0; i < expectedNumSpans - 1; i++) {
      assertEquals(0, sender.append(jaegerSpan));
    }

    // add a span that overflows the limit to hit the last branch
    int result = sender.append(jaegerSpan);
    assertEquals(expectedNumSpans, result);
  }

  @Test
  public void testFlushSendsSpan() throws Exception {
    Span expectedSpan = (Span) tracer.buildSpan("raza").startManual();

    assertEquals(0, sender.append(expectedSpan));
    assertEquals(1, sender.flush());

    List<List<zipkin.Span>> traces = zipkinRule.getTraces();
    assertEquals(traces.size(), 1);
    assertEquals(traces.get(0).size(), 1);

    zipkin.Span actualSpan = traces.get(0).get(0);
    SpanContext context = expectedSpan.context();

    assertEquals(context.getTraceId(), actualSpan.traceId);
    assertEquals(context.getSpanId(), actualSpan.id);
    assertEquals(context.getParentId(), (long) actualSpan.parentId);
    assertEquals(expectedSpan.getOperationName(), actualSpan.name);
    for (BinaryAnnotation binaryAnnotation : actualSpan.binaryAnnotations) {
      assertEquals(tracer.getServiceName(), binaryAnnotation.endpoint.serviceName);
    }
    for (Annotation annotation : actualSpan.annotations) {
      assertEquals(tracer.getServiceName(), annotation.endpoint.serviceName);
    }
  }

  @Test
  public void testAppendSpanWithLogs() throws Exception {
    Span span = (Span) tracer.buildSpan("span-with-logs").startManual();

    span.log("event");

    // use sorted map for consistent ordering in test
    Map<String, Object> fields = new TreeMap<String, Object>();
    fields.put("event", "structured data");
    fields.put("string", "something");
    fields.put("number", 42);
    fields.put("boolean", true);
    span.log(fields);

    sender.append(span);
    sender.flush();

    List<List<zipkin.Span>> traces = zipkinRule.getTraces();
    assertEquals(1, traces.size());
    assertEquals(1, traces.get(0).size());

    zipkin.Span zipkinSpan = traces.get(0).get(0);
    assertEquals(2, zipkinSpan.annotations.size());

    // ignore order by using set
    Set<String> annotationValues = new HashSet<String>();
    for (Annotation annotation : zipkinSpan.annotations) {
      annotationValues.add(annotation.value);
    }

    Set<String> expectedValues = new HashSet<String>();
    expectedValues.add("event");
    expectedValues.add("{\"boolean\":true,\"event\":\"structured data\",\"number\":42,\"string\":\"something\"}");

    assertEquals("zipkin span should contain matching annotations for span logs", expectedValues, annotationValues);
  }

  private ZipkinSender newSender(int messageMaxBytes) {
    return ZipkinSender.create(
        URLConnectionSender.builder()
            .messageMaxBytes(messageMaxBytes)
            .endpoint(zipkinRule.httpUrl() + "/api/v1/spans")
            .build());
  }
}
