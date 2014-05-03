/******************************************************************************
 ** This data and information is proprietary to, and a valuable trade secret
 ** of, Basis Technology Corp.  It is given in confidence by Basis Technology
 ** and may only be used as permitted under the license agreement under which
 ** it has been distributed, and in no other way.
 **
 ** Copyright (c) 2014 Basis Technology Corporation All rights reserved.
 **
 ** The technical data and information provided herein are provided with
 ** `limited rights', and the computer software provided herein is provided
 ** with `restricted rights' as those terms are defined in DAR and ASPR
 ** 7-104.9(a).
 ******************************************************************************/


package com.basistech.t5build;

import au.com.bytecode.opencsv.CSVParser;
import com.basistech.rosette.internal.take5build.Take5BuildException;
import com.basistech.rosette.internal.take5build.Take5Builder;
import com.basistech.rosette.internal.take5build.Take5EntryPoint;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.CharSource;
import com.google.common.io.Files;
import com.google.common.io.LineProcessor;
import org.apache.commons.io.FileUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.FileOptionHandler;
import org.kohsuke.args4j.spi.Setter;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Map;

/**
 * Command line interface for take5 builder.
 */
public final class Take5Build {

    private static final File NO_FILE = new File(".fnord.");

    enum Engine {
        FSA, // T5
        LOOKUP // trie? Do we even support this in Java?
        // TODO: new hash system.
    }

    public static class FileOrDashOptionHandler extends FileOptionHandler {

        public FileOrDashOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super File> setter) {
            super(parser, option, setter);
        }

        @Override
        protected File parse(String argument) throws CmdLineException {
            if ("-".equals(argument)) {
                return NO_FILE;
            } else {
                return super.parse(argument);
            }
        }

        @Override
        public String getDefaultMetaVariable() {
            return "INPUT_FILE_OR_-";
        }
    }

    //THERE IS NO -8

    @Argument(handler = FileOrDashOptionHandler.class, usage = "input file or - for standard input", metaVar = "INPUT")
    File commandInputFile;

    @Option(name = "--help", aliases = {"-h" })
    boolean help;

    @Option(name = "--output", aliases = {"-o" }, metaVar = "OUTPUT_FILE", handler = FileOrDashOptionHandler.class,
            usage = "output file")
    File outputFile;

    @Option(name = "--metadata", aliases = {"-k" },
            usage = "metadata content")
    File metadataFile;

    @Option(name = "--copyright", metaVar = "COPYRIGHT_FILE",
            usage = "File containing a copyright notice")
    File copyrightFile;

    // related to -5
    @Option(name = "--engine", metaVar = "ENGINE",
            usage = "lookup engine")
    Engine engine = Engine.FSA;

    @Option(name = "--join", aliases = {"-j" }, metaVar = "CONTROL_FILE", usage = "Combine multiple Take5's into one output.")
    File controlFile;

    @Option(name = "--binaryPayloads", aliases = {"-p" }, metaVar = "SIZE",
            usage = "payload size/alignment")
    int alignment;

    @Option(name = "--defaultMode", aliases = {"-d" }, metaVar = "MODE",
            usage = "default payload mode (e.g. #4f).")
    String defaultPayloadFormat;

    // payloads are in the file, but we ignore them.
    @Option(name = "--ignore-payloads", aliases = {"-i" }, usage = "ignore payloads")
    boolean ignorePayloads;

    // no payloads in the input _at all_
    @Option(name = "--no-payloads", usage = "no payloads")
    boolean noPayloads;

    //This is the inverse of -q.
    @Option(name = "--simpleKeys", usage = "simple keys")
    boolean simpleKeys;

    @Option(name = "--entrypoint", metaVar = "NAME", usage = "entrypoint")
    String entrypointName;

    @Option(name = "--contentVersion", aliases = {"-v" }, metaVar = "VERSION",
            usage = "version of content")
    int version;

    @Option(name = "--indexLookup", aliases = {"-x" },
            usage = "lookups map to (sorted) key indices")
    boolean indexLookup;

    @Option(name = "--debug-dump-lookup", aliases = {"-s" },
            usage = "write textual lookup dump.")
    boolean dumpLookup;

    @Option(name = "--content-flags", aliases = {"-f" }, metaVar = "FLAGS",
            usage = "content flags")
    int contentFlags;

    @Option(name = "--no-output", aliases = {"-n" },
            usage = "No output at all.")
    boolean noOutput;

    private List<InputSpecification> inputSpecifications;
    private Take5Builder builder;

    static class Failure extends Exception {
        Failure() {
        }

        Failure(String message) {
            super(message);
        }

        Failure(String message, Throwable cause) {
            super(message, cause);
        }

        Failure(Throwable cause) {
            super(cause);
        }
    }

    private Take5Build() {
        //
    }

    public static void main(String[] args) {
        Take5Build that = new Take5Build();
        CmdLineParser parser = new CmdLineParser(that);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            // handling of wrong arguments
            System.err.println(e.getMessage());
            parser.printUsage(System.err);
            System.exit(1);
        }

        if (that.help) {
            parser.printUsage(System.out);
            return;
        }

        try {
            that.checkOptionConsistency();
            that.build();
        } catch (Failure failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        }

    }

    private void checkOptionConsistency() throws Failure {
        if (controlFile != null) {
            try {
                inputSpecifications = new ControlFile().read(Files.asCharSource(controlFile, Charsets.UTF_8));
            } catch (IOException e) {
                throw new Failure(String.format("Failed to read control file %s.", controlFile.getAbsolutePath()), e);
            } catch (InputFileException e) {
                throw new Failure(String.format("Error in control file %s: %s.", controlFile.getAbsolutePath(),
                        e.getMessage()), e);
            }
        } else {
            inputSpecifications = Lists.newArrayList();
            InputSpecification spec = new InputSpecification();
            spec.contentFlags = contentFlags;
            spec.minVersion = version;
            spec.maxVersion = version;
            spec.defaultMode = defaultPayloadFormat;
            spec.entrypointName = entrypointName;
            spec.inputFile = commandInputFile;
            spec.simpleKeys = simpleKeys;
            spec.noPayloads = noPayloads;
            spec.ignorePayloads = ignorePayloads;
            inputSpecifications.add(spec);
        }

        if ((outputFile == null || outputFile == NO_FILE) && !dumpLookup) {
            throw new Failure("You must provide an output file unless you specify --debug-dump-lookup");
        }
    }

    private void build() throws Failure {
        Take5Builder.Factory factory = new Take5Builder.Factory();
        // our default is to write something.
        factory.outputFormat(Take5Builder.OutputFormat.TAKE5);
        if (noOutput) {
            factory.outputFormat(Take5Builder.OutputFormat.NONE);
        } else if (dumpLookup) {
            factory.outputFormat(Take5Builder.OutputFormat.FSA);
        } else if (noPayloads || ignorePayloads) {
            factory.valueFormat(Take5Builder.ValueFormat.INDEX);
        } else if (alignment != 0) {
            factory.valueFormat(Take5Builder.ValueFormat.PTR);
        } else {
            // if you don't specify an alignment with -p or -i to say no payloads at all.
            factory.valueFormat(Take5Builder.ValueFormat.IGNORE);
        }

        try {
            builder = factory.valueSize(alignment).build();
        } catch (Take5BuildException e) {
            throw new Failure(e);
        }
        copyright();

        metadata();

        for (InputSpecification spec : inputSpecifications) {
            String name = spec.entrypointName;
            if (name == null) {
                name = "main";
            }
            Take5EntryPoint entrypoint = null;
            try {
                entrypoint = builder.newEntryPoint(name);
            } catch (Take5BuildException e) {
                throw new Failure(e);
            }
            oneEntrypoint(spec, entrypoint);
        }

        try {
            if (dumpLookup) {
                builder.formatDescription(new OutputStreamWriter(System.out, Charsets.UTF_8));
            } else {
                builder.buildToSink(Files.asByteSink(outputFile));
            }
        } catch (IOException e) {
            throw new Failure("Failed to write output " + outputFile.getAbsolutePath());
        }
    }

    private void oneEntrypoint(InputSpecification spec, Take5EntryPoint entrypoint) throws Failure {
        CharSource source;
        if (spec.inputFile == NO_FILE) {
            source = new StdinByteSource().asCharSource(Charsets.UTF_8);
        } else {
            source = Files.asCharSource(spec.inputFile, Charsets.UTF_8);
        }
        InputFile inputFile = new InputFile();
        inputFile.setSimpleKeys(spec.simpleKeys);
        inputFile.setPayloads(!spec.noPayloads);
        inputFile.setIgnorePayloads(spec.ignorePayloads);
        try {
            inputFile.read(source); // pull the whole thing into memory.
        } catch (IOException e) {
            throw new Failure("IO error reading " + (spec.inputFile == NO_FILE ? "standard input" : spec.inputFile.getAbsolutePath()));
        } catch (InputFileException e) {
            throw new Failure(String.format("Format error reading %s: %s",
                    spec.inputFile == NO_FILE ? "standard input" : spec.inputFile.getAbsolutePath(),
                    e.getCause().getMessage()));
        }
        try {
            entrypoint.loadContent(inputFile.getPairs().iterator());
        } catch (Take5BuildException e) {
            throw new Failure(e);
        }
    }

    private void metadata() throws Failure {
        if (metadataFile != null) {
            CharSource metaSource = Files.asCharSource(metadataFile, Charsets.UTF_8);
            try {
                builder.setMetadata(metaSource.readLines(new LineProcessor<Map<String, String>>() {
                    private final Map<String, String> results = Maps.newHashMap();
                    private final CSVParser parser = new CSVParser('\t');

                    @Override
                    public boolean processLine(String line) throws IOException {
                        String[] tokens = parser.parseLine(line);
                        results.put(tokens[0], tokens[1]);
                        return true;
                    }

                    @Override
                    public Map<String, String> getResult() {
                        return results;
                    }
                }));
            } catch (IOException e) {
                throw new Failure("Failed to read metadata" + metadataFile.getAbsolutePath(), e);
            } catch (Take5BuildException e) {
                throw new Failure("Error in metadata " + metadataFile.getAbsolutePath(), e);
            }
        }
    }

    private void copyright() throws Failure {
        if (copyrightFile != null) {
            try {
                builder.setCopyright(FileUtils.readFileToString(copyrightFile, "ASCII"));
            } catch (IOException e) {
                throw new Failure("Failed to read copyright file " + copyrightFile.getAbsolutePath());
            }
        }
    }
}
