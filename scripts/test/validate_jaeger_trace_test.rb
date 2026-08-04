# frozen_string_literal: true

require "minitest/autorun"
require_relative "../validate-jaeger-trace"

class ValidateJaegerTraceTest < Minitest::Test
  TRACE_ID = "a" * 32

  def test_accepts_complete_single_root_trace_without_plaintext_fields
    result = validator.validate(payload, trace_id: TRACE_ID,
      required_spans: JaegerTraceValidator::FULL_SPANS,
      privacy_markers: ["private-marker"])

    assert result.valid?, result.errors.join("\n")
    assert_equal "01", result.root_span_id
  end

  def test_rejects_missing_required_span
    data = payload
    data["data"][0]["spans"].reject! { |span| span["operationName"] == "agent.intent.arbitrate" }

    result = validator.validate(data, trace_id: TRACE_ID)

    refute result.valid?
    assert_includes result.errors, "required span missing: agent.intent.arbitrate"
  end

  def test_deterministic_a2a_profile_does_not_require_model_routing_spans
    data = payload
    data["data"][0]["spans"].reject! do |span|
      %w[agent.intent.recall agent.intent.arbitrate].include?(span["operationName"])
    end
    task_span = data["data"][0]["spans"].find do |span|
      span["operationName"] == "agent.task.orchestrate"
    end
    rewrite_span = data["data"][0]["spans"].find do |span|
      span["operationName"] == "agent.context.rewrite"
    end
    task_span["references"][0]["spanID"] = rewrite_span["spanID"]

    result = validator.validate(data, trace_id: TRACE_ID,
      required_spans: JaegerTraceValidator::DETERMINISTIC_A2A_SPANS)

    assert result.valid?, result.errors.join("\n")
  end

  def test_context_short_circuit_profile_stops_before_recall_and_execution
    data = payload
    data["data"][0]["spans"].select! do |span|
      span["operationName"] == "http.server" ||
        JaegerTraceValidator::CONTEXT_SHORT_CIRCUIT_SPANS.include?(span["operationName"])
    end

    result = validator.validate(data, trace_id: TRACE_ID,
      required_spans: JaegerTraceValidator::CONTEXT_SHORT_CIRCUIT_SPANS)

    assert result.valid?, result.errors.join("\n")
    refute_includes result.span_names, "agent.intent.recall"
    refute_includes result.span_names, "agent.task.orchestrate"
  end

  def test_rejects_missing_parent_and_split_roots
    data = payload
    target = data["data"][0]["spans"].find do |span|
      span["operationName"] == "agent.a2a.server.execute"
    end
    target["references"] = [{"refType" => "CHILD_OF", "traceID" => TRACE_ID, "spanID" => "ff"}]

    result = validator.validate(data, trace_id: TRACE_ID)

    refute result.valid?
    assert result.errors.any? { |error| error.include?("missing parent ff") }
  end

  def test_rejects_cross_trace_parent
    data = payload
    data["data"][0]["spans"][3]["references"][0]["traceID"] = "b" * 32

    result = validator.validate(data, trace_id: TRACE_ID)

    refute result.valid?
    assert result.errors.any? { |error| error.include?("cross-trace parent") }
  end

  def test_rejects_sensitive_tag_names_even_without_a_marker
    data = payload
    data["data"][0]["spans"][0]["tags"] <<
      {"key" => "agent.raw_query", "type" => "string", "value" => "查余额"}

    result = validator.validate(data, trace_id: TRACE_ID)

    refute result.valid?
    assert result.errors.any? { |error| error.include?("agent.raw_query") }
  end

  def test_rejects_marker_in_span_log_and_process_tag
    data = payload
    data["data"][0]["spans"][1]["logs"] = [
      {"fields" => [{"key" => "event", "type" => "string", "value" => "private-marker"}]}
    ]
    data["data"][0]["processes"]["p1"]["tags"] <<
      {"key" => "deployment", "type" => "string", "value" => "private-marker"}

    result = validator.validate(data, trace_id: TRACE_ID,
      privacy_markers: ["private-marker"])

    refute result.valid?
    assert_equal 2, result.errors.count { |error| error.include?("privacy marker leaked") }
  end

  private

  def validator
    JaegerTraceValidator.new
  end

  def payload
    names = ["http.server"] + JaegerTraceValidator::FULL_SPANS
    spans = names.each_with_index.map do |name, index|
      id = format("%02x", index + 1)
      parent_id = index.zero? ? nil : format("%02x", index)
      {
        "traceID" => TRACE_ID,
        "spanID" => id,
        "operationName" => name,
        "references" => parent_id ? [
          {"refType" => "CHILD_OF", "traceID" => TRACE_ID, "spanID" => parent_id}
        ] : [],
        "processID" => index < 7 ? "p1" : "p2",
        "tags" => [
          {"key" => "agent.context.state_version", "type" => "int64", "value" => 3},
          {"key" => "a2a.capability", "type" => "string", "value" => "cap.account.balance.query"}
        ],
        "logs" => []
      }
    end
    {
      "data" => [{
        "traceID" => TRACE_ID,
        "spans" => spans,
        "processes" => {
          "p1" => {"serviceName" => "mobile-banking-assistant", "tags" => []},
          "p2" => {"serviceName" => "agent.account", "tags" => []}
        }
      }]
    }
  end
end
