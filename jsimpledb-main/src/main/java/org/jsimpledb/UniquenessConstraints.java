
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb;

/**
 * JSR 303 validation group for JSimpleDB uniqueness constraints.
 *
 * <p>
 * Explicit use of this class only needed if you wish to manually trigger validation of uniqueness constraints only,
 * for example:
 *  <pre>
 *  user.setUsername(u);
 *  user.revalidate(UniquenessConstraints.class);
 *  user.getTransaction().validate();           // throws exception if 'u' is not unique
 *  </pre>
 *
 * @see org.jsimpledb.annotation.JField#unique
 * @see JObject#revalidate JObject.revalidate()
 * @see JTransaction#validate JTransaction.validate()
 */
public interface UniquenessConstraints {
}

