# Response Time Analysis

The response card groups measured work into `RAG`, `Model`, and `Output`.
Durations use Android/Java and C++ monotonic clocks and are displayed in both
seconds and milliseconds.

## Important timing boundaries

- `Total` starts when `RagEngine.ask(...)` begins and ends immediately before
  the completed `ChatAnswer` is returned to `MainActivity`.
- `First token latency (SEND → token)` starts when the user presses `SEND` and
  stops when the first non-blank generated model token reaches the Java
  streaming callback.
- First-token latency is an end-to-end latency indicator. It overlaps RAG,
  model input processing, and prefill, so it is not added to any category total.
- The three category totals add up to `Total`. Uninstrumented pipeline time is
  assigned to `Output orchestration overhead`.
- For a composite question, context and native model timings from both model
  passes are accumulated. First-token latency still refers only to the first
  non-blank token from the first pass.

## RAG

`RAG` measures the work required to turn the user's question into grounded
manual evidence.

### Input preparation

Starts when `RagEngine.ask(...)` receives the input and ends after:

- handling a null value;
- trimming leading and trailing whitespace;
- checking the minimum input length;
- screening for the configured prompt-injection patterns.

It does not include tokenization for the embedding or conversational model.

### Query embedding

Measures the complete `LocalEmbedder.embed(...)` call:

- MiniLM WordPiece tokenization;
- creation of padded ONNX tensors;
- ONNX Runtime inference;
- attention-mask mean pooling;
- L2 normalization of the 384-dimensional vector.

### Hybrid retrieval

Measures `ManualRepository.hybridSearch(...)`, including:

- the native sqlite-vec cosine-distance scan;
- loading candidate chunk metadata from SQLite;
- extracting semantic section-title feedback;
- the original and expanded FTS5 searches;
- reciprocal-rank fusion and coverage bonuses;
- result sorting and per-page diversity limiting.

### Evidence validation

Measures the strong-evidence threshold check. A result must satisfy either the
hybrid lexical/semantic threshold or the close semantic-only threshold.

This stage does not validate generated answer text.

### Evidence routing

Measures the construction of the evidence set:

- selecting the primary result;
- detecting a separate uncovered need for a composite question;
- loading adjacent chunks from the same section;
- resolving directly referenced sections;
- merging and deduplicating primary and complementary evidence.

## Model

`Model` is the wall-clock duration of the complete companion-answer phase.
It includes Java context preparation, all native generation passes, citation
attachment, grounding validation, JNI work, and any wait for the serialized
native model.

### First token latency (SEND → token)

Measures the user's perceived time to the beginning of model output:

1. the `SEND` button handler records the start;
2. the request waits for the RAG worker if it is busy;
3. RAG retrieval and routing run;
4. model input preparation and prompt prefill run;
5. the first non-blank generated token reaches the Java callback.

This metric overlaps other stages and is therefore shown for diagnosis but
excluded from the `Model` total calculation.

### Context selection

Measures `RagEngine.buildSummaryContext(...)`:

- removing Markdown plumbing from evidence;
- splitting evidence into complete sentences;
- scoring sentences against query terms and action/safety language;
- prioritizing the focused section;
- enforcing the five-sentence and 900-character limits;
- restoring the selected sentences to manual order.

For a composite question, this work is measured separately and accumulated for
both focused model passes.

### Prompt formatting

Measured inside `summary_llm.cpp`. It includes:

- removing the previous dynamic turn from the llama.cpp KV cache while keeping
  the warm system-prompt prefix;
- converting the Java question and context strings to native UTF-8;
- constructing the Qwen ChatML user and assistant turn.

Waiting to acquire the process-wide model mutex occurs before this timer and is
reported under `Model orchestration`.

### Tokenization

Measures llama.cpp prompt tokenization. The tokenizer is called first to obtain
the required token count and then again to write the actual prompt token IDs.

This is conversational-model tokenization and is separate from MiniLM
tokenization under `Query embedding`.

### Prompt prefill

Measures the llama.cpp decode of all dynamic prompt tokens against the retained
warm system-prompt KV prefix. It ends when logits for the first output token are
available.

On CPU, this is usually the largest contributor to first-token latency.

### Token generation

Accumulates native work required to produce output tokens:

- greedy token sampling;
- conversion of sampled token IDs to UTF-8 pieces;
- llama.cpp decode of each generated token before sampling the next token.

It excludes the prompt prefill and Java streaming callback.

### Token streaming

Accumulates the synchronous JNI delivery cost for every generated piece:

- creating the Java token string;
- invoking `TokenListener.onToken(...)`;
- appending the token to the Java partial response;
- scheduling the partial UI update;
- deleting the JNI local string reference.

The actual main-thread drawing can happen after the callback returns and is not
included.

### Output finalization

Measures native work performed after token generation stops:

- checking whether the result ends with a complete sentence;
- emitting the native generation diagnostic log;
- accepting or discarding incomplete generated text;
- releasing the per-request llama.cpp sampler.

The final JNI string creation and timing callback are counted under
`Model orchestration`.

### Grounding validation

Measures Java validation of the completed candidate:

- refusal, prompt-leakage, fragment, and length checks;
- citation-page validation;
- numeric fact comparison against retrieved evidence;
- warning-color comparison against evidence on cited pages.

If validation fails, the previously prepared complete manual context becomes
the visible answer.

### Model orchestration

Calculated as:

```text
Model total
- Context selection
- Prompt formatting
- Tokenization
- Prompt prefill
- Token generation
- Token streaming
- Output finalization
- Grounding validation
```

It contains small uninstrumented operations such as:

- waiting for the serialized native model mutex;
- JNI callback and method lookup setup;
- sampler construction;
- per-token completeness and early-stop checks;
- verified citation attachment;
- passing native timing data back to Java;
- trimming and returning the native result.

## Output

`Output` contains response preparation that is outside retrieval and model
generation.

### Fallback/context formatting

Measures construction of the complete cited manual fallback:

- grouping evidence by section;
- removing internal Markdown markers;
- deduplicating chunk IDs;
- retaining complete chunk text;
- adding verified page labels.

This fallback is prepared for every grounded request before model generation,
even when the generated answer is ultimately accepted.

### Image lookup

Measures:

- extracting explicit figure references from the selected evidence;
- resolving referenced figures in `MANUAL_IMAGES`;
- searching captions on evidence and neighboring pages;
- ranking, deduplicating, and limiting returned images.

Image decoding and rendering in `MainActivity` are not included.

### Output orchestration overhead

Calculated as the remaining non-negative pipeline time:

```text
Total - RAG - Model - Fallback/context formatting - Image lookup
```

It normally contains small costs such as `ChatAnswer` construction and
unmeasured control flow between timed phases.

It does not include:

- actual Android main-thread layout or drawing;
- full-size image decoding after a user taps a figure;
- text-to-speech synthesis or playback;
- work performed after `RagEngine.ask(...)` returns.

## How to interpret the report

- High `Query embedding`: MiniLM/ONNX input processing or inference is slow.
- High `Hybrid retrieval`: vector scanning, FTS5, or storage access dominates.
- High `Prompt prefill`: reduce dynamic prompt tokens or improve model/runtime
  prefill performance.
- High `Token generation`: the model emits many tokens or has low
  tokens-per-second performance.
- High `Token streaming`: Java callback or partial-response UI scheduling is
  expensive.
- High `Model orchestration`: look for model-lock contention or uninstrumented
  JNI work.
- High `Image lookup`: figure/caption SQL resolution is expensive.
- High first-token latency with normal individual stages: inspect worker queue
  delay, because first-token latency starts at `SEND` while `Total` starts at
  `RagEngine.ask(...)`.
