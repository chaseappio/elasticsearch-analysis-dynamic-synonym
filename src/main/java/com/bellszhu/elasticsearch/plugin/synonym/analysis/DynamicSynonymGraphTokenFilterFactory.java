package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.*;

public class DynamicSynonymGraphTokenFilterFactory extends DynamicSynonymTokenFilterFactory {

    public DynamicSynonymGraphTokenFilterFactory(IndexSettings indexSettings,
                                                 Environment    env,
                                                 String         name,
                                                 Settings       settings) throws IOException {
        super(indexSettings, env, name, settings);
    }

    /** generic `create()` should never be called */
    @Override public TokenStream create(TokenStream in) {
        throw new IllegalStateException(
                "Call getChainAwareTokenFilterFactory(...) first");
    }

    @Override
    public TokenFilterFactory getChainAwareTokenFilterFactory(
            IndexService.IndexCreationContext context,
            TokenizerFactory tokenizer,
            List<CharFilterFactory> charFilters,
            List<TokenFilterFactory> previousFilters,
            Function<String, TokenFilterFactory> allFilters) {

        final Analyzer analyzer = buildSynonymAnalyzer(
                tokenizer, charFilters, previousFilters);

        synonymMap = buildSynonyms(analyzer);
        final String       myName = name();
        final AnalysisMode mode   = getAnalysisMode();

        return new TokenFilterFactory() {

            @Override public String name() { return myName; }

            @Override
            public TokenStream create(TokenStream in) {
                if (synonymMap.fst == null) return in;

                DynamicSynonymGraphFilter f =
                        new DynamicSynonymGraphFilter(in, synonymMap, false);
                dynamicFilters.put(f, 1);
                return f;
            }

            @Override public TokenFilterFactory getSynonymFilter() { return IDENTITY_FILTER; }

            @Override public AnalysisMode getAnalysisMode() { return mode; }
        };
    }

    /* helper used by the method above */
    protected Analyzer buildSynonymAnalyzer(TokenizerFactory tokenizer,
                                          List<CharFilterFactory> charFilters,
                                          List<TokenFilterFactory> tokenFilters) {
        return super.buildSynonymAnalyzer(tokenizer, charFilters, tokenFilters);
    }
}
