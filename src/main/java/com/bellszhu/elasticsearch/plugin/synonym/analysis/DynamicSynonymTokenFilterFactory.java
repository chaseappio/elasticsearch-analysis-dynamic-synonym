package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.*;

public class DynamicSynonymTokenFilterFactory extends AbstractTokenFilterFactory {

    private static final Logger logger = LogManager.getLogger("dynamic-synonym");

    /* ---------- static helpers ---------------------------------------------------- */

    private static final AtomicInteger ID_GEN = new AtomicInteger(1);
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r);
                t.setName("monitor-synonym-" + ID_GEN.getAndIncrement());
                t.setDaemon(true);
                return t;
            });

    /* ---------- ctor-supplied config --------------------------------------------- */

    private final IndexSettings indexSettings;
    private final Environment  environment;

    private final String   location;
    private final boolean  ignoreCase;      // <-- NEW (needed by DynamicSynonymFilter)
    private final boolean  expand;
    private final boolean  lenient;
    private final String   format;
    private final int      interval;

    private final AnalysisMode analysisMode;

    /* ---------- runtime state ----------------------------------------------------- */
    protected volatile SynonymMap synonymMap; 
    protected final Map<AbsSynonymFilter,Integer> dynamicFilters = new WeakHashMap<>();
    private volatile ScheduledFuture<?> monitorTask;

    /* ---------- constructor ------------------------------------------------------- */

    public DynamicSynonymTokenFilterFactory(IndexSettings indexSettings,
                                            Environment    env,
                                            String         name,
                                            Settings       settings) throws IOException {
        super(name);

        this.indexSettings = indexSettings;
        this.environment   = env;

        this.location   = settings.get("synonyms_path");
        if (location == null) {
            throw new IllegalArgumentException("dynamic synonym requires `synonyms_path`");
        }

        this.ignoreCase = settings.getAsBoolean("ignore_case", false);
        this.expand     = settings.getAsBoolean("expand",      true);
        this.lenient    = settings.getAsBoolean("lenient",     false);
        this.format     = settings.get("format",               "");
        this.interval   = settings.getAsInt("interval",        60);

        boolean updateable = settings.getAsBoolean("updateable", false);
        this.analysisMode  = updateable ? AnalysisMode.SEARCH_TIME : AnalysisMode.ALL;
    }

    /* ---------- simple passthroughs ---------------------------------------------- */

    @Override public AnalysisMode getAnalysisMode() { return analysisMode; }

    /** The generic‐SPI `create()` must *not* be used directly. */
    @Override public TokenStream create(TokenStream in) {
        throw new IllegalStateException(
                "Call getChainAwareTokenFilterFactory(...) first");
    }

    /* ---------- ES 8.11+ SPI entry-point ----------------------------------------- */
    @Override
    public TokenFilterFactory getChainAwareTokenFilterFactory(
            IndexService.IndexCreationContext context,
            TokenizerFactory tokenizer,
            List<CharFilterFactory> charFilters,
            List<TokenFilterFactory> previousFilters,
            Function<String, TokenFilterFactory> allFilters) {

        /* Build the analyser that will read the synonym file */
        Analyzer synonymAnalyzer = buildSynonymAnalyzer(tokenizer, charFilters, previousFilters);

        /* Parse synonym file (and start monitor if interval > 0) */
        this.synonymMap = buildSynonyms(synonymAnalyzer);

        /* Name/analysis-mode captured for inner factory */
        final String       myName = name();
        final AnalysisMode mode   = analysisMode;

        return new TokenFilterFactory() {

            @Override public String name() { return myName; }

            @Override
            public TokenStream create(TokenStream in) {
                /* No synonyms? – passthrough */
                if (synonymMap.fst == null) return in;

                DynamicSynonymFilter f =
                        new DynamicSynonymFilter(in, synonymMap, ignoreCase);
                dynamicFilters.put(f, 1);
                return f;
            }

            /** Prevent filters that explode the synonym map */
            @Override public TokenFilterFactory getSynonymFilter() { return IDENTITY_FILTER; }

            @Override public AnalysisMode getAnalysisMode() { return mode; }
        };
    }

    /* ---------- helper methods ---------------------------------------------------- */

    protected Analyzer buildSynonymAnalyzer(TokenizerFactory tokenizer,
                                          List<CharFilterFactory> charFilters,
                                          List<TokenFilterFactory> tokenFilters) {
        return new CustomAnalyzer(
                tokenizer,
                charFilters.toArray(CharFilterFactory[]::new),
                tokenFilters.stream()
                            .map(TokenFilterFactory::getSynonymFilter)
                            .toArray(TokenFilterFactory[]::new));
    }

    protected SynonymMap buildSynonyms(Analyzer analyzer) {
        try { return getSynonymFile(analyzer).reloadSynonymMap(); }
        catch (Exception e) {
            logger.error("building synonyms failed", e);
            throw new IllegalArgumentException("cannot build synonyms", e);
        }
    }

    private SynonymFile getSynonymFile(Analyzer analyzer) {
        try {
            SynonymFile file = (location.startsWith("http://")
                                || location.startsWith("https://"))
                    ? new RemoteSynonymFile(indexSettings.getIndex().getName(),
                                            environment, analyzer, expand, lenient, format, location)
                    : new LocalSynonymFile(environment, analyzer, expand, lenient, format, location);

            if (monitorTask == null && interval > 0) {
                monitorTask = SCHEDULER.scheduleAtFixedRate(new Monitor(file),
                                                            interval, interval, TimeUnit.SECONDS);
            }
            return file;
        } catch (Exception e) {
            logger.error("loading synonym file [{}] failed", location, e);
            throw new IllegalArgumentException("cannot load synonym file: " + location, e);
        }
    }

    /* ---------- periodic monitor -------------------------------------------------- */

    private class Monitor implements Runnable {
        private final SynonymFile synonymFile;
        Monitor(SynonymFile file) { this.synonymFile = file; }

        @Override public void run() {
            try {
                if (synonymFile.isNeedReloadSynonymMap()) {
                    synonymMap = synonymFile.reloadSynonymMap();
                    dynamicFilters.keySet().forEach(f -> f.update(synonymMap));
                    logger.debug("synonym map reloaded");
                }
            } catch (Exception e) {
                logger.warn("synonym reload failed", e);
            }
        }
    }

    /* ---------- compatibility shim for ES SPI ------------------------------------ */

    /** Needed by the SPI – delegates to the 3-arg variant above. */
    private Analyzer buildSynonymAnalyzer(IndexService.IndexCreationContext ctx,
                                          TokenizerFactory tok,
                                          List<CharFilterFactory> cf,
                                          List<TokenFilterFactory> tf) {
        return buildSynonymAnalyzer(tok, cf, tf);
    }
}
