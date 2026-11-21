/**
 * Multi-tenancy infrastructure and tenant context management.
 *
 * <p>This package provides the core multi-tenancy implementation using PostgreSQL
 * schema-per-tenant isolation. It handles tenant identification, schema resolution,
 * and ensures that tenant connection state never leaks between pooled connections.
 *
 * <p>Key concepts:
 * <ul>
 *   <li>Tenant context propagation and validation</li>
 *   <li>Schema-per-tenant isolation enforcement</li>
 *   <li>Cross-tenant access prevention</li>
 *   <li>Tenant-aware data access</li>
 * </ul>
 *
 * @since 1.0.0
 */
package com.docukore.tenant;