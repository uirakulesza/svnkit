/*
 * ====================================================================
 * Copyright (c) 2004-2010 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://svnkit.com/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.internal.wc17.db.statement;

import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.internal.db.SVNSqlJetDb;
import org.tmatesoft.svn.core.internal.db.SVNSqlJetSelectFieldsStatement;

/**
 * SELECT properties, presence FROM nodes WHERE wc_id = ?1 AND local_relpath =
 * ?2 AND op_depth > 0 ORDER BY op_depth DESC LIMIT 1;
 *
 * @author TMate Software Ltd.
 */
public class SVNWCDbSelectWorkingProperties extends SVNSqlJetSelectFieldsStatement<SVNWCDbSchema.NODES__Fields> {

    public SVNWCDbSelectWorkingProperties(SVNSqlJetDb sDb) throws SVNException {
        super(sDb, SVNWCDbSchema.NODES);
    }

    protected void defineFields() {
        fields.add(SVNWCDbSchema.NODES__Fields.properties);
        fields.add(SVNWCDbSchema.NODES__Fields.presence);
    }

    protected boolean isFilterPassed() throws SVNException {
        return getColumnLong(SVNWCDbSchema.NODES__Fields.op_depth) > 0;
    }

}
