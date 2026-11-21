/**
 * Root package for the Docukore medical practice management platform.
 *
 * <p>This is a modular monolith built with Spring Boot and Spring Modulith.
 * The application provides patient management, chronological medical records,
 * consultations, appointments, prescriptions, simplified accounting, and staff
 * management for private medical practices.
 *
 * <p>Architecture:
 * <ul>
 *   <li>Domain-driven modular structure with explicit boundaries</li>
 *   <li>PostgreSQL schema-per-tenant isolation for multi-tenancy</li>
 *   <li>Platform schema for shared metadata, tenant schemas for clinical data</li>
 * </ul>
 *
 * @since 1.0.0
 */
package com.docukore;