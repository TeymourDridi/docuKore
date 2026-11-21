/**
 * Cabinet access control and membership management.
 *
 * <p>This package handles authorization and access control for medical cabinet members.
 * It defines the security model for doctor and secretary roles within a cabinet,
 * ensuring that secretary accounts never automatically inherit doctor permissions.
 *
 * <p>Key concepts:
 * <ul>
 *   <li>Cabinet membership and role assignment</li>
 *   <li>Permission validation for clinical operations</li>
 *   <li>Tenant-scoped access control</li>
 * </ul>
 *
 * @since 1.0.0
 */
package com.docukore.cabinet.access;