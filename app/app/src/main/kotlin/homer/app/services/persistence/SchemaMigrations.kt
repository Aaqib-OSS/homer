package homer.app.services.persistence

import java.sql.Statement

// In-place schema upgrades [DatabaseService.migrate] runs after `SchemaUtils.create`, one function
// per span of versions. `SchemaUtils.create` never ALTERs an existing table, so each step adds its
// columns/indexes by hand, guarded on the version it upgrades from.

internal fun upgradeSchema(
    st: Statement,
    current: Int,
) {
    migrateToV10(st, current)
    migrateToV15(st, current)
    migrateToV20(st, current)
    migrateToV24(st, current)
    migrateToV27(st, current)
    migrateToV28(st, current)
    migrateToV30(st, current)
    migrateToV31(st, current)
    migrateToV32(st, current)
    migrateToV33(st, current)
}

private fun columnNames(
    st: Statement,
    table: String,
): Set<String> =
    st.executeQuery("PRAGMA table_info($table)").use { rs ->
        generateSequence { if (rs.next()) rs.getString("name") else null }.toSet()
    }

private fun columnDefault(
    st: Statement,
    table: String,
    column: String,
): String? =
    st.executeQuery("PRAGMA table_info($table)").use { rs ->
        generateSequence { if (rs.next()) rs.getString("name") to rs.getString("dflt_value") else null }
            .firstOrNull { (name, _) -> name == column }
            ?.second
    }

/** Adds [column] to [table] unless it already has it — SchemaUtils.create never ALTERs an existing
 * table. Returns whether the column was added, for a step that backfills it. */
private fun addColumnIfMissing(
    st: Statement,
    table: String,
    column: String,
    definition: String,
): Boolean {
    if (column in columnNames(st, table)) return false
    st.execute("ALTER TABLE $table ADD COLUMN $column $definition")
    return true
}

private fun migrateToV10(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 2) {
        // v1→v2 adds questions.dispatch_bound; create adds the dispatches table but never ALTERs,
        // so add the column by hand.
        addColumnIfMissing(st, "questions", "dispatch_bound", "INTEGER NOT NULL DEFAULT 0")
    }
    if (current in 3 until 5) {
        // v4→v5 adds the continuation_watches table (created above) and the continuations.kind
        // column; create never ALTERs an existing table, so add the column by hand.
        addColumnIfMissing(st, "continuations", "kind", "TEXT NOT NULL DEFAULT 'SINGLE'")
    }
    if (current in 3 until 7) {
        // v6→v7 adds result_tokens.consumer_agent (audience binding, ADR-0025); create never ALTERs,
        // so add it by hand. The empty default makes a pre-existing token match no agent — it fails
        // closed until swept; v33 rebuilds the table without it.
        addColumnIfMissing(st, "result_tokens", "consumer_agent", "TEXT NOT NULL DEFAULT ''")
    }
    if (current in 1 until 8) {
        // v7→v8 adds process/dispatch/continuation owner columns (ADR-0038). Lower bound is 1, not
        // 3: processes has existed since v1, so a DB frozen at v1/v2 already has a processes table
        // SchemaUtils.create won't ALTER.
        for (t in listOf("processes", "dispatches", "continuations")) {
            addColumnIfMissing(st, t, "owner", "TEXT")
        }
    }
    if (current in 3 until 10) {
        // v9→v10 adds continuations.fire_attempts / next_attempt_at (transient-failure retry);
        // create never ALTERs. Existing rows default to 0 attempts and no scheduled retry.
        addColumnIfMissing(st, "continuations", "fire_attempts", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(st, "continuations", "next_attempt_at", "INTEGER")
    }
}

private fun migrateToV15(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 11) {
        // v10→v11 adds processes.trace_id (Langfuse deep link). Lower bound 1: processes has existed
        // since v1.
        addColumnIfMissing(st, "processes", "trace_id", "VARCHAR(32)")
    }
    if (current in 1 until 12) {
        // v11→v12 adds processes.record_purge_at. Existing rows keep NULL, which the sweep reads as
        // "same as purge_at".
        addColumnIfMissing(st, "processes", "record_purge_at", "INTEGER")
    }
    if (current in 1 until 13) {
        // v12→v13 adds processes.created_at, the cooldown's authoritative "last run started",
        // backfilled from the first history entry so the bound keeps holding across the upgrade.
        if (addColumnIfMissing(st, "processes", "created_at", "INTEGER")) {
            // Without this the cooldown would not bind for one run per agent after the upgrade.
            // Rows with an empty history keep NULL (json_extract returns NULL) = "never ran".
            st.execute(
                "UPDATE processes SET created_at = json_extract(history, '\$[0][0]') " +
                    "WHERE created_at IS NULL",
            )
        }
    }
    if (current in 1 until 14) {
        // v13→v14 adds processes.ended_at, the exact terminal time the `cooldown.afterFailure`
        // window is measured from, backfilled from the last history entry of terminal rows.
        if (addColumnIfMissing(st, "processes", "ended_at", "INTEGER")) {
            // Backfill the terminal time for terminal rows only; an active row has no terminal time
            // yet and must stay NULL.
            st.execute(
                "UPDATE processes SET ended_at = json_extract(history, '\$[#-1][0]') " +
                    "WHERE ended_at IS NULL " +
                    "AND status IN ('FINISHED', 'FAILED', 'KILLED', 'UNKNOWN')",
            )
        }
    }
    if (current in 1 until 15) {
        // v14→v15 adds processes.secret_input_keys, the redaction metadata captured at run time.
        // Existing rows keep NULL and fall back to the current agent definition, not a blanket
        // redaction of every retained record.
        addColumnIfMissing(st, "processes", "secret_input_keys", "TEXT")
    }
}

private fun migrateToV20(
    st: Statement,
    current: Int,
) {
    if (current in 3 until 16) {
        // v15→v16 adds secret_input_keys to dispatches and continuations — the target agent's secret
        // flags captured at registration, so a definition edited before the row fires cannot expose a
        // stored value. Lower bound 3: both tables arrive complete from create for anything older.
        for (t in listOf("dispatches", "continuations")) {
            addColumnIfMissing(st, t, "secret_input_keys", "TEXT")
        }
    }
    if (current in 3 until 17) {
        // v16→v17 adds secret_param_names to dispatches and continuations — the target agent's secret
        // paramNames captured at registration. `params` are keyed by paramName, so envName-keyed
        // secret_input_keys alone cannot survive the input being re-pointed since registration (ADR-0052).
        for (t in listOf("dispatches", "continuations")) {
            addColumnIfMissing(st, t, "secret_param_names", "TEXT")
        }
    }
    if (current in 1 until 18) {
        // v17→v18 adds the retain-block columns (ADR-0053): the globs captured at terminal status,
        // the workspace-sweep marker for retained rows, and the cap-truncation flag. Existing rows
        // keep NULL = nothing retained.
        addColumnIfMissing(st, "processes", "retain_artifacts", "TEXT")
        addColumnIfMissing(st, "processes", "workspace_purged_at", "INTEGER")
        addColumnIfMissing(st, "processes", "retained_truncated", "INTEGER")
    }
    if (current in 1 until 19) {
        // v18→v19 adds processes.parent_process_id (ADR-0058). Existing rows keep NULL = no known parent.
        addColumnIfMissing(st, "processes", "parent_process_id", "INTEGER")
    }
}

private fun migrateToV24(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 21) {
        // v20→v21 adds processes.cost_source (ADR-0061); 'cli' is the truthful default for existing rows.
        addColumnIfMissing(st, "processes", "cost_source", "TEXT NOT NULL DEFAULT 'cli'")
    }
    if (current in 1 until 22) {
        // v21→v22 adds processes.root_process_id (ADR-0058). No backfill: NULL reads as "flow root unknown".
        addColumnIfMissing(st, "processes", "root_process_id", "INTEGER")
    }
    if (current in 1 until 23) {
        // v22→v23 indexes the process-list filters; create only indexes a table it creates.
        st.execute("CREATE INDEX IF NOT EXISTS processes_agent_name ON processes (agent_name)")
        st.execute("CREATE INDEX IF NOT EXISTS processes_status ON processes (status)")
        st.execute("CREATE INDEX IF NOT EXISTS processes_parent_process_id ON processes (parent_process_id)")
        st.execute("CREATE INDEX IF NOT EXISTS processes_root_process_id ON processes (root_process_id)")
        st.execute("CREATE INDEX IF NOT EXISTS processes_owner ON processes (owner)")
    }
    if (current in 1 until 24) {
        // v23→v24 indexes the sweep, cooldown and lookup filters; names/definitions match Tables.kt.
        st.execute("CREATE INDEX IF NOT EXISTS processes_artifacts_dir ON processes (artifacts_dir)")
        st.execute(
            "CREATE INDEX IF NOT EXISTS processes_workspace_purge ON processes (status, purge_at) " +
                "WHERE (artifacts_dir IS NOT NULL) AND (workspace_purged_at IS NULL)",
        )
        st.execute("CREATE INDEX IF NOT EXISTS processes_record_purge ON processes (status, record_purge_at)")
        st.execute(
            "CREATE INDEX IF NOT EXISTS processes_legacy_record_purge ON processes (status, purge_at) " +
                "WHERE record_purge_at IS NULL",
        )
        st.execute(
            "CREATE INDEX IF NOT EXISTS processes_agent_status_ended ON processes (agent_name, status, ended_at)",
        )
        st.execute(
            "CREATE INDEX IF NOT EXISTS questions_status_dispatch_bound ON questions (status, dispatch_bound, process_id)",
        )
        st.execute(
            "CREATE INDEX IF NOT EXISTS questions_process_dispatch_bound ON questions (process_id, dispatch_bound)",
        )
        st.execute("CREATE INDEX IF NOT EXISTS dispatches_status_expires ON dispatches (status, expires_at)")
        st.execute(
            "CREATE INDEX IF NOT EXISTS continuations_watched_status ON continuations (watched_process_id, status)",
        )
        st.execute("CREATE INDEX IF NOT EXISTS continuations_status_expires ON continuations (status, expires_at)")
    }
}

private fun migrateToV27(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 25) {
        // v24→v25 moves write-once input_params into the process_inputs side table (created above),
        // guarded on the column so a database that already migrated must not attempt a second
        // backfill or DROP. See ADR-0020.
        if ("input_params" in columnNames(st, "processes")) {
            // Copied before the drop, in this same transaction, so no committed state ever has the
            // column gone and the value nowhere. COALESCE guards a hand-edited or pre-v1 NULL row:
            // the side table's column is NOT NULL.
            st.execute(
                "INSERT INTO process_inputs (process_id, input_params) " +
                    "SELECT id, COALESCE(input_params, '{}') FROM processes",
            )
            st.execute("ALTER TABLE processes DROP COLUMN input_params")
        }
    }
    if (current in 1 until 26) {
        // v25→v26 indexes the settled-row prune and the paginated list filters of dispatches and
        // continuations; names/definitions match Tables.kt.
        st.execute("CREATE INDEX IF NOT EXISTS dispatches_status_created ON dispatches (status, created_at)")
        st.execute("CREATE INDEX IF NOT EXISTS dispatches_agent_name ON dispatches (agent_name)")
        st.execute("CREATE INDEX IF NOT EXISTS dispatches_owner ON dispatches (owner)")
        st.execute("CREATE INDEX IF NOT EXISTS continuations_status_created ON continuations (status, created_at)")
        st.execute("CREATE INDEX IF NOT EXISTS continuations_agent_name ON continuations (agent_name)")
        st.execute("CREATE INDEX IF NOT EXISTS continuations_owner ON continuations (owner)")
    }
    if (current in 1 until 27) {
        // v26→v27 adds continuations.thread_id, backfilled from params, and its index. json_valid:
        // a hand-edited row whose params are not JSON keeps NULL instead of failing the upgrade.
        addColumnIfMissing(st, "continuations", "thread_id", "TEXT")
        st.execute(
            "UPDATE continuations SET thread_id = json_extract(params, '\$.HMR_LG_THREAD') " +
                "WHERE thread_id IS NULL AND json_valid(params)",
        )
        st.execute("CREATE INDEX IF NOT EXISTS continuations_thread_id ON continuations (thread_id)")
    }
}

private fun migrateToV28(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 28) {
        st.execute("CREATE INDEX IF NOT EXISTS questions_agent_name ON questions (agent_name)")
    }
}

/** v29→v30 adds the stored pre-start failure reason and the run it names. Its own version, not
 * main's v29 (`run_messages`): a database that release stamped `user_version = 29` skips every step
 * guarded below 30 when it has no column to add, so folding both into v29 would strand its columns. */
private fun migrateToV30(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 30) {
        // No backfill: a guessed reason is worse than none.
        addColumnIfMissing(st, "processes", "failure_reason", "TEXT")
        addColumnIfMissing(st, "processes", "failure_reason_run_id", "INTEGER")
    }
}

/** v30→v31 adds the per-subject cooldown key and its two indexes (ADR-0106), the graceful-stop
 * blob (ADR-0108) and the `pending_hooks` indexes (ADR-0107) — one step, so a database stamped 31
 * skips the whole span. No backfill: a guessed subject or cause is worse than none. */
private fun migrateToV31(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 31) {
        addColumnIfMissing(st, "processes", "cooldown_key", "TEXT")
        st.execute("CREATE INDEX IF NOT EXISTS processes_agent_cooldown_key ON processes (agent_name, cooldown_key)")
        st.execute(
            "CREATE INDEX IF NOT EXISTS processes_agent_cooldown_status_ended " +
                "ON processes (agent_name, cooldown_key, status, ended_at)",
        )
        addColumnIfMissing(st, "processes", "stop", "TEXT")
        // The pending_hooks indexes (ADR-0107): create above made the table, but only indexes a
        // table it creates, so they are made here by hand. Names/definitions match Tables.kt.
        st.execute(
            "CREATE INDEX IF NOT EXISTS pending_hooks_status_next_attempt ON pending_hooks (status, next_attempt_at)",
        )
        st.execute("CREATE INDEX IF NOT EXISTS pending_hooks_process_id ON pending_hooks (process_id)")
        st.execute("CREATE INDEX IF NOT EXISTS pending_hooks_status_created ON pending_hooks (status, created_at)")
    }
}

/** v31→v32 adds grouped ask-and-dispatch (ADR-0109): the `dispatches.kind` column and the
 * `dispatch_group_questions` member-lookup index. Its own version, not v31: a database stamped 31
 * skips steps guarded below 32. No backfill — `SINGLE` is the truthful default. */
private fun migrateToV32(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 32) {
        // create above already created dispatch_group_questions but never ALTERs an existing table,
        // so the column and the member-lookup index are added by hand (ADR-0109).
        addColumnIfMissing(st, "dispatches", "kind", "TEXT NOT NULL DEFAULT 'SINGLE'")
        st.execute(
            "CREATE INDEX IF NOT EXISTS dispatch_group_questions_question_id " +
                "ON dispatch_group_questions (question_id)",
        )
    }
}

/** v32→v33 drops the `DEFAULT ''` the v7 step gave `result_tokens.consumer_agent`, so an upgraded
 * database rejects an audience-less INSERT like a fresh one. SQLite cannot drop a default in place,
 * so the table is rebuilt from its `Tables.kt` DDL, every row kept; guarded on the default itself. */
private fun migrateToV33(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 33 && columnDefault(st, "result_tokens", "consumer_agent") != null) {
        // The old table moves aside first so the generated DDL runs verbatim; no trigger, view or
        // foreign key names it.
        val columns = ResultTokens.columns.joinToString(", ") { it.name }
        st.execute("ALTER TABLE result_tokens RENAME TO result_tokens_v32")
        ResultTokens.ddl.forEach(st::execute)
        st.execute("INSERT INTO result_tokens ($columns) SELECT $columns FROM result_tokens_v32")
        st.execute("DROP TABLE result_tokens_v32")
    }
}
