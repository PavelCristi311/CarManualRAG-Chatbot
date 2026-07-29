package com.atlas.manualassistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String TAG = "AtlasTTFT";
    private static final int MICROPHONE_PERMISSION_REQUEST = 41;
    private static final Pattern TIMING_HEADER = Pattern.compile(
            "(?m)^(Response time analysis|RAG —.*|Model —.*|"
                    + "Output —.*|Total:.*)$");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService voiceWorker = Executors.newSingleThreadExecutor();
    private RagEngine engine;
    private volatile ZipformerVoiceInput voiceInput;
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private Button microphone;
    private ProgressBar progress;
    private boolean voiceRecording;
    private TextToSpeech textToSpeech;
    private boolean ttsReady;
    private String pendingSpeech;
    private volatile boolean destroyed;

    /** Builds the screen and warms all offline engines away from the UI thread. */
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        initializeTts();
        initializeVoiceInput();
        addAssistantMessage(getString(R.string.initial_message), null);
        worker.execute(() -> {
            try {
                engine = new RagEngine(getApplicationContext());
                runOnUiThreadIfActive(() -> {
                    progress.setVisibility(View.GONE);
                    send.setEnabled(true);
                    microphone.setEnabled(voiceInput != null);
                });
            } catch (Exception error) {
                runOnUiThreadIfActive(() -> addAssistantMessage(
                        getString(R.string.database_error), null));
            }
        });
    }

    /** Prepares offline speech recognition independently from the RAG engine. */
    private void initializeVoiceInput() {
        voiceWorker.execute(() -> {
            ZipformerVoiceInput prepared =
                    new ZipformerVoiceInput(getApplicationContext());
            try {
                prepared.prepare();
                if (destroyed) {
                    prepared.close();
                    return;
                }
                voiceInput = prepared;
                runOnUiThreadIfActive(() -> microphone.setEnabled(engine != null));
            } catch (Throwable error) {
                prepared.close();
                if (!destroyed) {
                    runOnUiThreadIfActive(() -> addAssistantMessage(
                            getString(R.string.voice_initialization_error), null));
                }
            }
        });
    }

    /** Composes the screen from focused header, transcript, and input views. */
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.surface));
        root.addView(buildHeader(), matchWidthWrapHeight());
        root.addView(buildMessageArea(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildComposer());
        return root;
    }

    /** Creates the compact application identity header. */
    private View buildHeader() {
        int padding = dp(16);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(padding, dp(14), padding, dp(12));
        header.setBackgroundColor(getColor(R.color.atlas_blue));
        TextView title = text(getString(R.string.app_name), 20, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = text(getString(R.string.subtitle), 12, 0xFFD9E8F5);
        header.addView(title);
        header.addView(subtitle);
        return header;
    }

    /** Creates the scrollable transcript and stores its mutable containers. */
    private View buildMessageArea() {
        int padding = dp(16);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(padding, padding, padding, padding);
        scroll.addView(messages);
        return scroll;
    }

    /** Creates the text, progress, microphone, and send controls. */
    private View buildComposer() {
        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(10), dp(8), dp(10), dp(10));
        composer.setBackgroundColor(Color.WHITE);
        input = new EditText(this);
        input.setHint(R.string.hint);
        input.setTextSize(16);
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        composer.addView(input, new LinearLayout.LayoutParams(0, dp(52), 1f));
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        composer.addView(progress, new LinearLayout.LayoutParams(dp(36), dp(36)));
        microphone = new Button(this);
        microphone.setText(R.string.microphone);
        microphone.setAllCaps(false);
        microphone.setEnabled(false);
        microphone.setContentDescription(getString(R.string.voice_start_description));
        microphone.setOnClickListener(unused -> toggleVoiceInput());
        composer.addView(microphone, new LinearLayout.LayoutParams(dp(72), dp(52)));
        send = new Button(this);
        send.setText(R.string.send);
        send.setEnabled(false);
        send.setOnClickListener(unused -> submit());
        composer.addView(send, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)));
        return composer;
    }

    /** Returns the standard full-width layout parameters used by top-level rows. */
    private static LinearLayout.LayoutParams matchWidthWrapHeight() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** Starts, stops, or requests permission for microphone input. */
    private void toggleVoiceInput() {
        if (voiceRecording) {
            stopVoiceInput();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MICROPHONE_PERMISSION_REQUEST);
            return;
        }
        startVoiceInput();
    }

    /** Switches into recording mode and streams ASR partial results. */
    private void startVoiceInput() {
        if (engine == null || voiceInput == null || voiceRecording) return;
        if (textToSpeech != null) textToSpeech.stop();
        voiceRecording = true;
        input.setText("");
        input.setHint(R.string.loading_speech);
        microphone.setText(R.string.stop_recording);
        microphone.setContentDescription(getString(R.string.voice_stop_description));
        send.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        voiceWorker.execute(() -> voiceInput.recordUntilStopped(
                new ZipformerVoiceInput.Listener() {
                    @Override
                    public void onListeningStarted() {
                        runOnUiThreadIfActive(() -> {
                            if (!voiceRecording) return;
                            input.setHint(R.string.listening);
                            progress.setVisibility(View.GONE);
                        });
                    }

                    @Override
                    public void onPartialResult(String text) {
                        runOnUiThreadIfActive(() -> {
                            if (!voiceRecording) return;
                            input.setText(text);
                            input.setSelection(input.length());
                        });
                    }

                    @Override
                    public void onFinalResult(String text) {
                        runOnUiThreadIfActive(() -> finishVoiceInput(text, null));
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThreadIfActive(() -> finishVoiceInput(
                                "", getString(R.string.voice_input_error, message)));
                    }
                }));
    }

    /** Requests a graceful ASR stop so buffered audio is decoded. */
    private void stopVoiceInput() {
        if (!voiceRecording || voiceInput == null) return;
        voiceRecording = false;
        microphone.setText(R.string.voice_stopping);
        microphone.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        voiceInput.requestStop();
    }

    /** Restores controls and submits a successful normalized transcript. */
    private void finishVoiceInput(String transcript, String error) {
        voiceRecording = false;
        microphone.setText(R.string.microphone);
        microphone.setEnabled(engine != null && voiceInput != null);
        microphone.setContentDescription(getString(R.string.voice_start_description));
        input.setHint(R.string.hint);
        progress.setVisibility(View.GONE);
        send.setEnabled(engine != null);
        if (error != null) {
            addAssistantMessage(error, null);
            return;
        }
        String normalizedTranscript = VoiceQueryNormalizer.normalize(transcript);
        input.setText(normalizedTranscript);
        input.setSelection(input.length());
        if (normalizedTranscript.trim().length() >= 2) submit();
    }

    /** Runs one question on the worker and paints model tokens incrementally. */
    private void submit() {
        long submittedNanos = System.nanoTime();
        String question = input.getText().toString().trim();
        if (question.length() < 2 || engine == null) return;
        input.setText("");
        hideKeyboard();
        addUserMessage(question);
        send.setEnabled(false);
        microphone.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        TextView pending = addAssistantMessage(getString(R.string.searching_manual), null);
        worker.execute(() -> {
            StringBuilder streamed = new StringBuilder();
            long[] firstModelTokenNanos = {0L};
            ChatAnswer answer = engine.ask(question, token -> {
                if (token == null || token.isEmpty()) return;
                if (firstModelTokenNanos[0] == 0L && !token.isBlank()) {
                    firstModelTokenNanos[0] =
                            System.nanoTime() - submittedNanos;
                    Log.d(
                            TAG,
                            "send-to-first-model-token="
                                    + (firstModelTokenNanos[0] / 1_000_000L)
                                    + "ms");
                }
                streamed.append(token);
                String partial = streamed.toString().trim();
                if (partial.isEmpty()
                        || !partial.matches("(?s).*\\p{L}.*")) {
                    return;
                }
                runOnUiThreadIfActive(() -> {
                    pending.setText(partial);
                    scrollToBottom();
                });
            });
            answer.timings.firstModelTokenNanos =
                    firstModelTokenNanos[0];
            runOnUiThreadIfActive(() -> {
                View pendingCard = (View) pending.getParent();
                messages.removeView((View) pendingCard.getParent());
                addAssistantMessage(answer.text, answer);
                speakAnswer(answer.text);
                progress.setVisibility(View.GONE);
                send.setEnabled(true);
                microphone.setEnabled(voiceInput != null);
            });
        });
    }

    /** Adds a selectable user bubble and keeps the latest turn visible. */
    private void addUserMessage(String text) {
        LinearLayout row = row(Gravity.END);
        TextView bubble = userBubble(text);
        row.addView(bubble, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        messages.addView(row);
        scrollToBottom();
    }

    /** Adds an assistant card and enriches completed answers with evidence metadata. */
    private TextView addAssistantMessage(String text, ChatAnswer answer) {
        LinearLayout row = row(Gravity.START);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(11), dp(14), dp(11));
        card.setBackground(rounded(Color.WHITE, 16));
        TextView bubble = text(text, 15, getColor(R.color.text_primary));
        bubble.setTextIsSelectable(true);
        card.addView(bubble);

        if (answer != null) {
            appendImages(card, answer);
            appendSources(card, answer);
            appendTimings(card, answer);
        }
        row.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        messages.addView(row);
        scrollToBottom();
        return bubble;
    }

    /** Adds tappable thumbnails for figures connected to retrieved chunks. */
    private void appendImages(LinearLayout card, ChatAnswer answer) {
        for (ManualImage image : answer.images) {
            ImageView view = loadImage(image.thumbnailPath);
            if (view == null) continue;
            view.setAdjustViewBounds(true);
            view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            view.setPadding(0, dp(10), 0, dp(4));
            view.setOnClickListener(unused -> showImage(image));
            card.addView(view, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(190)));
            card.addView(text(
                    getString(R.string.image_caption, image.caption, image.page),
                    12,
                    getColor(R.color.text_secondary)));
        }
    }

    /** Adds a collapsible view of every manual chunk behind the answer. */
    private void appendSources(LinearLayout card, ChatAnswer answer) {
        if (answer.sources.isEmpty()) return;
        Button sources = new Button(this);
        sources.setAllCaps(false);
        sources.setText(getResources().getQuantityString(
                R.plurals.manual_source_count,
                answer.sources.size(),
                answer.sources.size()));
        TextView details = text(
                sourceText(answer), 12, getColor(R.color.text_secondary));
        details.setVisibility(View.GONE);
        sources.setOnClickListener(unused -> details.setVisibility(
                details.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));
        card.addView(sources);
        card.addView(details);
    }

    /** Shows phase timings so device performance remains observable. */
    private void appendTimings(LinearLayout card, ChatAnswer answer) {
        String report = answer.timings.detailedText();
        TextView timing = text(
                report,
                11,
                getColor(R.color.text_secondary));
        timing.setText(styleTimingReport(report));
        timing.setPadding(0, dp(8), 0, 0);
        timing.setTextIsSelectable(true);
        card.addView(timing);
    }

    /** Emphasizes category totals so a long timing report remains easy to scan. */
    private SpannableString styleTimingReport(String report) {
        SpannableString styled = new SpannableString(report);
        Matcher matcher = TIMING_HEADER.matcher(report);
        while (matcher.find()) {
            styled.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    matcher.start(),
                    matcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(
                    new ForegroundColorSpan(getColor(R.color.atlas_blue)),
                    matcher.start(),
                    matcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    /** Renders source chunks in page order for the expandable evidence view. */
    private String sourceText(ChatAnswer answer) {
        StringBuilder output = new StringBuilder();
        for (SearchResult source : answer.sources) {
            if (output.length() > 0) output.append("\n\n");
            output.append("p. ").append(source.page);
            if (!source.section.isEmpty()) output.append(" — ").append(source.section);
            output.append('\n').append(source.text);
        }
        return output.toString();
    }

    /** Decodes a bundled manual image, returning null for a damaged asset. */
    private ImageView loadImage(String relativePath) {
        try (InputStream stream = getAssets().open("manual_assets/" + relativePath)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            return image;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Opens a full-resolution manual figure in a dismissible dialog. */
    private void showImage(ManualImage manualImage) {
        ImageView image = loadImage(manualImage.assetPath);
        if (image == null) return;
        image.setAdjustViewBounds(true);
        image.setPadding(dp(8), dp(8), dp(8), dp(8));
        new AlertDialog.Builder(this)
                .setTitle(getString(
                        R.string.image_dialog_title,
                        manualImage.caption,
                        manualImage.page))
                .setView(image)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    /** Creates a consistently spaced chat row aligned to either speaker. */
    private LinearLayout row(int gravity) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(gravity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        row.setLayoutParams(params);
        return row;
    }

    /** Styles the user's message bubble. */
    private TextView userBubble(String value) {
        TextView text = text(value, 15, getColor(R.color.text_primary));
        text.setTextIsSelectable(true);
        text.setPadding(dp(14), dp(10), dp(14), dp(10));
        text.setBackground(rounded(getColor(R.color.user_bubble), 16));
        return text;
    }

    /** Creates text with the app's common line spacing. */
    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.1f);
        return view;
    }

    /** Creates a solid rounded background in density-independent units. */
    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    /** Defers scrolling until the latest layout pass has completed. */
    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Converts density-independent pixels to physical pixels. */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Hides the keyboard once a question is submitted. */
    private void hideKeyboard() {
        InputMethodManager manager = getSystemService(InputMethodManager.class);
        manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    /** Drops late callbacks after destruction instead of touching stale views. */
    private void runOnUiThreadIfActive(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed) action.run();
        });
    }

    /** Continues voice capture only after the requested microphone permission. */
    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MICROPHONE_PERMISSION_REQUEST) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput();
        } else {
            addAssistantMessage(
                    getString(R.string.microphone_permission_error), null);
        }
    }

    /** Selects and warms a fully offline English TTS voice. */
    private void initializeTts() {
        textToSpeech = new TextToSpeech(
                getApplicationContext(),
                status -> {
                    if (status != TextToSpeech.SUCCESS || textToSpeech == null) return;
                    Voice offlineVoice = chooseOfflineEnglishVoice(textToSpeech.getVoices());
                    if (offlineVoice == null
                            || textToSpeech.setVoice(offlineVoice) == TextToSpeech.ERROR) return;
                    textToSpeech.setSpeechRate(0.95f);
                    textToSpeech.setPitch(1.0f);
                    ttsReady = true;
                    if (pendingSpeech != null) {
                        String queued = pendingSpeech;
                        pendingSpeech = null;
                        speakAnswer(queued);
                    }
                });
    }

    /** Prefers deterministic US English voices that require no network. */
    private Voice chooseOfflineEnglishVoice(Set<Voice> voices) {
        if (voices == null) return null;
        return voices.stream()
                .filter(voice -> !voice.isNetworkConnectionRequired())
                .filter(voice -> "en".equalsIgnoreCase(voice.getLocale().getLanguage()))
                .min(Comparator
                        .comparing((Voice voice) ->
                                !"US".equalsIgnoreCase(voice.getLocale().getCountry()))
                        .thenComparing(Voice::getName))
                .orElse(null);
    }

    /** Converts visual citations to spoken page references and queues narration. */
    private void speakAnswer(String answer) {
        if (answer == null || answer.trim().isEmpty()) return;
        if (!ttsReady || textToSpeech == null) {
            pendingSpeech = answer;
            return;
        }
        String spoken = answer
                .replaceAll("\\[p\\.\\s*(\\d+)]", ". Page $1.")
                .replace('•', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        textToSpeech.speak(
                spoken,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "atlas-answer-" + System.nanoTime());
    }

    /** Stops native engines and background executors owned by the activity. */
    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        if (voiceInput != null) voiceInput.close();
        voiceWorker.shutdownNow();
        if (engine != null) engine.close();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        super.onDestroy();
    }
}
