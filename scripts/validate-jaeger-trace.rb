#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "net/http"
require "optparse"
require "uri"

class JaegerTraceValidator
  CONTEXT_SHORT_CIRCUIT_SPANS = %w[
    agent.context.load
    agent.context.compile
    agent.context.rewrite
  ].freeze
  ENTRY_SPANS = (CONTEXT_SHORT_CIRCUIT_SPANS + %w[
    agent.intent.recall
    agent.intent.arbitrate
    agent.task.orchestrate
  ]).freeze
  A2A_SPANS = %w[
    agent.a2a.client
    agent.a2a.delegate
    agent.a2a.gateway.route
    agent.a2a.server.execute
    agent.a2a.target.runtime
    agent.task.orchestrate
  ].freeze
  DETERMINISTIC_A2A_SPANS = (%w[
    agent.context.load
    agent.context.compile
    agent.context.rewrite
    agent.task.orchestrate
  ] + A2A_SPANS).uniq.freeze
  FULL_SPANS = (ENTRY_SPANS + A2A_SPANS).uniq.freeze

  SENSITIVE_TAG_SUFFIXES = %w[
    query raw_query user_query user_input prompt
    goal raw_goal amount
    principal principal_ref
    account account_ref account_number account_no card_number
    request_body response_body
  ].freeze

  Result = Struct.new(:errors, :span_names, :root_span_id, keyword_init: true) do
    def valid?
      errors.empty?
    end
  end

  def validate(payload, trace_id:, required_spans: FULL_SPANS, privacy_markers: [])
    errors = []
    trace = select_trace(payload, trace_id, errors)
    return Result.new(errors: errors, span_names: [], root_span_id: nil) unless trace

    spans = Array(trace["spans"])
    span_names = spans.map { |span| span["operationName"].to_s }
    required_spans.each do |name|
      errors << "required span missing: #{name}" unless span_names.include?(name)
    end

    trace_ids = spans.map { |span| span["traceID"].to_s }.reject(&:empty?).uniq
    errors << "spans do not share one traceID: #{trace_ids.join(',')}" unless trace_ids.size == 1
    if trace_id && trace_ids.one? && trace_ids.first != trace_id
      errors << "traceID mismatch: expected=#{trace_id} actual=#{trace_ids.first}"
    end

    span_by_id = spans.to_h { |span| [span["spanID"].to_s, span] }
    required_instances = spans.select { |span| required_spans.include?(span["operationName"].to_s) }
    roots = required_instances.map do |span|
      root_for(span, span_by_id, trace_ids.first, errors)
    end.compact.uniq
    if required_instances.any? && roots.size != 1
      errors << "required spans do not converge on one root: #{roots.join(',')}"
    end

    inspect_privacy(trace, privacy_markers, errors)
    Result.new(errors: errors.uniq, span_names: span_names.uniq.sort,
               root_span_id: roots.one? ? roots.first : nil)
  end

  private

  def select_trace(payload, trace_id, errors)
    traces = Array(payload && payload["data"])
    if traces.empty?
      errors << "Jaeger response contains no trace"
      return nil
    end
    return traces.first if trace_id.nil? || trace_id.empty?

    trace = traces.find do |candidate|
      Array(candidate["spans"]).any? { |span| span["traceID"].to_s == trace_id }
    end
    errors << "trace not found: #{trace_id}" unless trace
    trace
  end

  def root_for(span, span_by_id, expected_trace_id, errors)
    current = span
    seen = {}
    loop do
      span_id = current["spanID"].to_s
      if seen[span_id]
        errors << "parent cycle detected from #{span['operationName']}: #{span_id}"
        return nil
      end
      seen[span_id] = true

      parent_ref = Array(current["references"]).find do |ref|
        ref["refType"].to_s == "CHILD_OF"
      end
      return span_id unless parent_ref

      parent_trace = parent_ref["traceID"].to_s
      if !expected_trace_id.to_s.empty? && parent_trace != expected_trace_id
        errors << "cross-trace parent on #{current['operationName']}: #{parent_trace}"
        return nil
      end
      parent_id = parent_ref["spanID"].to_s
      parent = span_by_id[parent_id]
      unless parent
        errors << "missing parent #{parent_id} for #{current['operationName']}"
        return nil
      end
      current = parent
    end
  end

  def inspect_privacy(trace, markers, errors)
    Array(trace["spans"]).each do |span|
      inspect_fields(Array(span["tags"]), "span #{span['operationName']} tag", markers, errors)
      Array(span["logs"]).each_with_index do |log, index|
        inspect_fields(Array(log["fields"]), "span #{span['operationName']} log[#{index}]",
                       markers, errors)
      end
    end
    Hash(trace["processes"]).each do |process_id, process|
      inspect_fields(Array(process["tags"]), "process #{process_id} tag", markers, errors)
    end
  end

  def inspect_fields(fields, location, markers, errors)
    fields.each do |field|
      key = field["key"].to_s
      value = field["value"].to_s
      normalized = key.downcase.tr("-", "_")
      suffix = normalized.split(".").last.to_s
      if SENSITIVE_TAG_SUFFIXES.include?(suffix)
        errors << "sensitive plaintext field is forbidden at #{location}: #{key}"
      end
      markers.each do |marker|
        next if marker.to_s.empty?
        if key.include?(marker) || value.include?(marker)
          errors << "privacy marker leaked at #{location}: #{key}"
        end
      end
    end
  end
end

class JaegerTraceCli
  PROFILES = {
    "context-short-circuit" => JaegerTraceValidator::CONTEXT_SHORT_CIRCUIT_SPANS,
    "entry" => JaegerTraceValidator::ENTRY_SPANS,
    "a2a" => JaegerTraceValidator::A2A_SPANS,
    "deterministic-a2a" => JaegerTraceValidator::DETERMINISTIC_A2A_SPANS,
    "full" => JaegerTraceValidator::FULL_SPANS
  }.freeze

  def self.run(argv)
    options = {
      jaeger_url: ENV.fetch("JAEGER_URL", "http://localhost:16686"),
      profile: "full",
      privacy_markers: [],
      wait_seconds: 0
    }
    parser = OptionParser.new do |opts|
      opts.banner = "Usage: validate-jaeger-trace.rb --trace-id ID [options]"
      opts.on("--trace-id ID", "Expected trace id") { |value| options[:trace_id] = value }
      opts.on("--file PATH", "Read a saved Jaeger API response") { |value| options[:file] = value }
      opts.on("--jaeger-url URL", "Jaeger base URL") { |value| options[:jaeger_url] = value }
      opts.on("--profile NAME", PROFILES.keys.join("|")) { |value| options[:profile] = value }
      opts.on("--required-spans CSV", "Override required span names") do |value|
        options[:required_spans] = value.split(",").map(&:strip).reject(&:empty?)
      end
      opts.on("--privacy-marker VALUE", "Reject marker in tags/log fields; repeatable") do |value|
        options[:privacy_markers] << value
      end
      opts.on("--wait-seconds N", Integer, "Wait for Jaeger ingestion") { |value| options[:wait_seconds] = value }
    end
    parser.parse!(argv)

    unless PROFILES.key?(options[:profile])
      warn "[FAIL] unknown profile: #{options[:profile]}"
      return 2
    end
    if options[:trace_id].to_s.empty? && options[:file].to_s.empty?
      warn parser
      return 2
    end

    required = options[:required_spans] || PROFILES.fetch(options[:profile])
    deadline = Time.now + options[:wait_seconds]
    last_result = nil
    loop do
      payload = load_payload(options)
      last_result = JaegerTraceValidator.new.validate(payload,
        trace_id: options[:trace_id], required_spans: required,
        privacy_markers: options[:privacy_markers])
      break if last_result.valid? || Time.now >= deadline || options[:file]
      sleep 2
    rescue JSON::ParserError, IOError, SystemCallError, Net::HTTPError => e
      last_result = JaegerTraceValidator::Result.new(
        errors: ["cannot load Jaeger trace: #{e.message}"], span_names: [], root_span_id: nil)
      break if Time.now >= deadline || options[:file]
      sleep 2
    end

    if last_result.valid?
      puts "[PASS] Jaeger trace #{options[:trace_id] || '(fixture)'} profile=#{options[:profile]} " \
           "spans=#{required.size} root=#{last_result.root_span_id} privacy=clean"
      0
    else
      last_result.errors.each { |error| warn "[FAIL] #{error}" }
      1
    end
  end

  def self.load_payload(options)
    return JSON.parse(File.read(options[:file])) if options[:file]

    base = options[:jaeger_url].sub(%r{/+$}, "")
    uri = URI("#{base}/api/traces/#{options[:trace_id]}")
    response = Net::HTTP.start(uri.host, uri.port,
                               use_ssl: uri.scheme == "https",
                               open_timeout: 3, read_timeout: 10) { |http| http.get(uri.request_uri) }
    # Net::HTTPError requires a response object on older system Ruby versions.
    # IOError is already handled by the polling loop and preserves the status in diagnostics.
    raise IOError, "HTTP #{response.code}" unless response.is_a?(Net::HTTPSuccess)

    JSON.parse(response.body)
  end
end

exit JaegerTraceCli.run(ARGV) if $PROGRAM_NAME == __FILE__
