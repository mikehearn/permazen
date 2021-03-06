
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.cli.cmd;

import java.util.Map;

import org.jsimpledb.cli.CliSession;
import org.jsimpledb.util.ParseContext;

public class SetVerboseCommand extends AbstractCommand {

    public SetVerboseCommand() {
        super("set-verbose verbose:boolean");
    }

    @Override
    public String getHelpSummary() {
        return "Enables or disables verbose mode, which displays the complete stack trace when an exceptions occurs.";
    }

    @Override
    public CliSession.Action getAction(CliSession session0, ParseContext ctx, boolean complete, Map<String, Object> params) {
        final boolean verbose = (Boolean)params.get("verbose");
        return session -> {
            session.setVerbose(verbose);
            session.getWriter().println((verbose ? "En" : "Dis") + "abled verbose mode.");
        };
    }
}

