/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.workshop.guidance;

import com.fr3ts0n.ecu.gui.androbd.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only, bundled general triage. No guide represents a matched vehicle manual. */
public final class WorkshopGuideCatalog
{
    public interface Text { String get(int resource); }

    public static final class Guide
    {
        public final String id;
        public final String module;
        public final int title;
        public final int keywords;
        public final List<Integer> sections;
        public final List<Integer> sources;
        private final Set<String> codes;

        private Guide(String id, String module, int title, int keywords, int[] sections,
                      Integer[] sources, String... codes)
        {
            this.id = id;
            this.module = module;
            this.title = title;
            this.keywords = keywords;
            List<Integer> content = new ArrayList<>();
            for (int section : sections) content.add(section);
            this.sections = Collections.unmodifiableList(content);
            this.sources = Collections.unmodifiableList(Arrays.asList(sources));
            this.codes = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(codes)));
        }
    }

    public static final class Result
    {
        /** Topic/code matches only. A fallback is always separate and never an exact match. */
        public final List<Guide> guides;
        public final List<String> unmatchedCodes;
        public final Guide fallback;

        private Result(List<Guide> guides, List<String> unmatchedCodes, Guide fallback)
        {
            this.guides = Collections.unmodifiableList(guides);
            this.unmatchedCodes = Collections.unmodifiableList(unmatchedCodes);
            this.fallback = fallback;
        }
    }

    private static final Pattern CODE = Pattern.compile(
            "(?<![A-Z0-9])[PBCU][0-9A-F]{4}(?:[-:][0-9A-F]{2})?(?![A-Z0-9]|[-:][A-Z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Guide UNSUPPORTED = new Guide("unsupported", null,
            R.string.guide_unsupported_title, R.string.guide_unsupported_keywords,
            new int[]{R.string.guide_unsupported_symptoms, R.string.guide_unsupported_safety,
                    R.string.guide_unsupported_tools, R.string.guide_unsupported_checks,
                    R.string.guide_unsupported_reference, R.string.guide_unsupported_repairs,
                    R.string.guide_unsupported_verify},
            new Integer[]{R.string.guide_source_ford_warnings, R.string.guide_source_aston});
    private static final List<Guide> GUIDES = Collections.unmodifiableList(Arrays.asList(
            new Guide("underboost", "ENGINE", R.string.guide_boost_title,
                    R.string.guide_boost_keywords,
                    new int[]{R.string.guide_boost_symptoms, R.string.guide_boost_safety,
                            R.string.guide_boost_tools, R.string.guide_boost_checks,
                            R.string.guide_boost_reference, R.string.guide_boost_repairs,
                            R.string.guide_boost_verify},
                    new Integer[]{R.string.guide_source_garrett, R.string.guide_source_gm}, "P0299"),
            new Guide("overheating", "ENGINE", R.string.guide_overheat_title,
                    R.string.guide_overheat_keywords,
                    new int[]{R.string.guide_overheat_symptoms, R.string.guide_overheat_safety,
                            R.string.guide_overheat_tools, R.string.guide_overheat_checks,
                            R.string.guide_overheat_reference, R.string.guide_overheat_repairs,
                            R.string.guide_overheat_verify},
                    new Integer[]{R.string.guide_source_ford_warnings, R.string.guide_source_ford_coolant}),
            new Guide("srs", "SRS", R.string.guide_srs_title, R.string.guide_srs_keywords,
                    new int[]{R.string.guide_srs_symptoms, R.string.guide_srs_safety,
                            R.string.guide_srs_tools, R.string.guide_srs_checks,
                            R.string.guide_srs_reference, R.string.guide_srs_repairs,
                            R.string.guide_srs_verify},
                    new Integer[]{R.string.guide_source_aston, R.string.guide_source_toyota}),
            new Guide("transmission", "TRANSMISSION", R.string.guide_trans_title,
                    R.string.guide_trans_keywords,
                    new int[]{R.string.guide_trans_symptoms, R.string.guide_trans_safety,
                            R.string.guide_trans_tools, R.string.guide_trans_checks,
                            R.string.guide_trans_reference, R.string.guide_trans_repairs,
                            R.string.guide_trans_verify},
                    new Integer[]{R.string.guide_source_ford_warnings, R.string.guide_source_ford_trans})
    ));
    private final Text text;

    public WorkshopGuideCatalog(Text text) { this.text = text; }

    public Guide find(String id)
    {
        if (UNSUPPORTED.id.equals(id)) return UNSUPPORTED;
        for (Guide guide : GUIDES) if (guide.id.equals(id)) return guide;
        return null;
    }

    /** Null/empty module means all; an unknown nonempty module never broadens the scope. */
    public Result search(String module, String query)
    {
        String filter = module == null ? "" : module.trim().toUpperCase(Locale.ROOT);
        String input = query == null ? "" : query.trim();
        Matcher matcher = CODE.matcher(input);
        Set<String> codes = new LinkedHashSet<>();
        while (matcher.find()) codes.add(matcher.group().toUpperCase(Locale.ROOT));
        List<Guide> matches = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        if (!codes.isEmpty())
        {
            for (String code : codes)
            {
                boolean matched = false;
                for (Guide guide : GUIDES)
                {
                    if (allows(filter, guide) && guide.codes.contains(code))
                    {
                        if (!matches.contains(guide)) matches.add(guide);
                        matched = true;
                    }
                }
                if (!matched) unmatched.add(code);
            }
        }
        else
        {
            String[] words = input.toLowerCase(Locale.ROOT).split("\\s+");
            for (Guide guide : GUIDES)
            {
                if (!allows(filter, guide)) continue;
                String haystack = (text.get(guide.title) + " " + text.get(guide.keywords)
                        + " " + guide.module).toLowerCase(Locale.ROOT);
                boolean matched = true;
                for (String word : words) if (!haystack.contains(word)) matched = false;
                if (matched) matches.add(guide);
            }
        }
        return new Result(matches, unmatched,
                input.isEmpty() || matches.isEmpty() || !unmatched.isEmpty() ? UNSUPPORTED : null);
    }

    private static boolean allows(String filter, Guide guide)
    {
        return filter.isEmpty() || filter.equals(guide.module);
    }
}
