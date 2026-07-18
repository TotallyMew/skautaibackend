-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Auto-update updated_at trigger function
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
RETURN NEW;
END;
$$ language 'plpgsql';

-- Super admins
CREATE TABLE super_admins (
                              id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                              name VARCHAR(100) NOT NULL,
                              email VARCHAR(255) UNIQUE NOT NULL,
                              password_hash VARCHAR(255) NOT NULL,
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tuntai (
                        id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                        name VARCHAR(100) UNIQUE NOT NULL,
                        krastas VARCHAR(100),
                        contact_email VARCHAR(255),
                        status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'REJECTED', 'DELETED')),
                        approved_by_super_admin_id UUID REFERENCES super_admins(id),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        approved_at TIMESTAMP,
                        rejected_at TIMESTAMP
);

-- Users
CREATE TABLE users (
                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                       name VARCHAR(100) NOT NULL,
                       surname VARCHAR(100) NOT NULL,
                       email VARCHAR(255) UNIQUE NOT NULL,
                       password_hash VARCHAR(255) NOT NULL,
                       phone VARCHAR(20),
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       deleted_at TIMESTAMP
);

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE account_deletion_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    requested_via VARCHAR(20) NOT NULL CHECK (requested_via IN ('APP', 'WEB')),
    expires_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_account_deletion_requests_user
    ON account_deletion_requests(user_id, created_at DESC);
CREATE INDEX idx_account_deletion_requests_expiry
    ON account_deletion_requests(expires_at);

-- User tuntas memberships
CREATE TABLE user_tuntas_memberships (
                                         id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                         user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                         tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                         joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                         left_at TIMESTAMP,
                                         UNIQUE(user_id, tuntas_id)
);
-- Roles
CREATE TABLE roles (
                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                       tuntas_id UUID REFERENCES tuntai(id) ON DELETE CASCADE,
                       name VARCHAR(100) NOT NULL,
                       is_system_role BOOLEAN DEFAULT FALSE,
                       role_type VARCHAR(20) NOT NULL DEFAULT 'LEADERSHIP' CHECK (role_type IN ('LEADERSHIP', 'RANK')),
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Organizational units
CREATE TABLE organizational_units (
                                      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                      tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                      name VARCHAR(100) NOT NULL,
                                      type VARCHAR(40) NOT NULL,
                                      subtype VARCHAR(20),
                                      accepted_rank_id UUID REFERENCES roles(id),
                                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);



-- Permissions
CREATE TABLE permissions (
                             id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                             name VARCHAR(100) UNIQUE NOT NULL,
                             description TEXT,
                             context VARCHAR(20) NOT NULL CHECK (context IN ('GLOBAL', 'EVENT'))
);

-- Role permissions
CREATE TABLE role_permissions (
                                  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                  role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
                                  permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
                                  scope VARCHAR(20) DEFAULT 'ALL' CHECK (scope IN ('ALL', 'OWN_UNIT')),
                                  UNIQUE(role_id, permission_id)
);

-- User leadership roles
CREATE TABLE user_leadership_roles (
                                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                       user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                       role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
                                       tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                       organizational_unit_id UUID REFERENCES organizational_units(id),
                                       assigned_by_user_id UUID REFERENCES users(id),
                                       assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                       starts_at TIMESTAMP,
                                       expires_at TIMESTAMP,
                                       left_at TIMESTAMP,
                                       term_number INTEGER NOT NULL DEFAULT 1,
                                       term_status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (term_status IN ('ACTIVE', 'COMPLETED', 'RESIGNED'))
);

CREATE TABLE leadership_change_requests (
                                            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                            tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                            requester_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                            role_assignment_id UUID NOT NULL REFERENCES user_leadership_roles(id),
                                            role_id UUID NOT NULL REFERENCES roles(id),
                                            organizational_unit_id UUID NOT NULL REFERENCES organizational_units(id),
                                            status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
                                            reason TEXT,
                                            reviewed_by_user_id UUID REFERENCES users(id),
                                            successor_user_id UUID REFERENCES users(id),
                                            review_note TEXT,
                                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                            reviewed_at TIMESTAMP,
                                            resolved_assignment_id UUID REFERENCES user_leadership_roles(id)
);

-- User ranks
CREATE TABLE user_ranks (
                            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
                            tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                            assigned_by_user_id UUID REFERENCES users(id),
                            assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            UNIQUE(user_id, tuntas_id)
);

-- Locations
CREATE TABLE locations (
                           id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                           tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                           name VARCHAR(100) NOT NULL,
                           visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC' CHECK (visibility IN ('PRIVATE', 'UNIT', 'PUBLIC')),
                           parent_location_id UUID REFERENCES locations(id) ON DELETE RESTRICT,
                           owner_user_id UUID REFERENCES users(id),
                           owner_unit_id UUID REFERENCES organizational_units(id),
                           address TEXT,
                           description TEXT,
                           latitude DECIMAL(9,6),
                           longitude DECIMAL(9,6),
                           created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Items
CREATE TABLE items (
                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                       tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                       custodian_id UUID REFERENCES organizational_units(id),
                       origin VARCHAR(30) NOT NULL DEFAULT 'UNIT_ACQUIRED',
                       name VARCHAR(200) NOT NULL,
                       description TEXT,
                       type VARCHAR(20) NOT NULL CHECK (type IN ('COLLECTIVE', 'ASSIGNED', 'INDIVIDUAL')),
                       category VARCHAR(30) NOT NULL,
                       condition VARCHAR(30) DEFAULT 'GOOD',
                       quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity >= 0),
                       is_consumable BOOLEAN NOT NULL DEFAULT FALSE,
                       unit_of_measure VARCHAR(30) NOT NULL DEFAULT 'vnt.',
                       minimum_quantity INTEGER CHECK (minimum_quantity IS NULL OR minimum_quantity >= 0),
                       location_id UUID REFERENCES locations(id) ON DELETE SET NULL,
                       temporary_storage_label VARCHAR(255),
                       source_shared_item_id UUID REFERENCES items(id),
                       responsible_user_id UUID REFERENCES users(id),
                       created_by_user_id UUID REFERENCES users(id),
                       qr_token VARCHAR(36) NOT NULL DEFAULT uuid_generate_v4()::text,
                       photo_url TEXT,
                       purchase_date DATE,
                       purchase_price DECIMAL(10,2),
                       notes TEXT,
                       status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PENDING_APPROVAL', 'INACTIVE')),
                       submitted_by_user_id UUID REFERENCES users(id),
                       target_scope VARCHAR(10) CHECK (target_scope IN ('SHARED', 'UNIT')),
                       reviewed_by_user_id UUID REFERENCES users(id),
                       reviewed_at TIMESTAMP,
                       rejection_reason VARCHAR(500),
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER update_items_updated_at
    BEFORE UPDATE ON items
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Item addition requests
-- Item custom fields
CREATE TABLE item_custom_fields (
                                    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                    item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                                    field_name VARCHAR(100) NOT NULL,
                                    field_value TEXT
);

-- Item attachments
CREATE TABLE item_attachments (
                                  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                  item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                                  file_url TEXT NOT NULL,
                                  file_type VARCHAR(20) CHECK (file_type IN ('RECEIPT', 'INVOICE', 'PHOTO', 'OTHER')),
                                  uploaded_by_user_id UUID REFERENCES users(id),
                                  uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Item assignments
CREATE TABLE item_assignments (
                                  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                  item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                                  assigned_to_user_id UUID NOT NULL REFERENCES users(id),
                                  assigned_by_user_id UUID REFERENCES users(id),
                                  assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                  unassigned_at TIMESTAMP,
                                  reason TEXT,
                                  notes TEXT
);

-- Direct item loans without reservation
CREATE TABLE direct_item_loans (
                                   id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                   item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                                   tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                   issued_to_user_id UUID NOT NULL REFERENCES users(id),
                                   issued_by_user_id UUID NOT NULL REFERENCES users(id),
                                   quantity INTEGER NOT NULL CHECK (quantity > 0),
                                   returned_quantity INTEGER NOT NULL DEFAULT 0 CHECK (returned_quantity >= 0),
                                   status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                                   issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   returned_at TIMESTAMP,
                                   due_at TIMESTAMP,
                                   notes TEXT,
                                   CONSTRAINT chk_direct_item_loans_returned_quantity CHECK (returned_quantity <= quantity),
                                   CONSTRAINT chk_direct_item_loans_status CHECK (status IN ('ACTIVE', 'RETURNED'))
);

-- Item condition log
CREATE TABLE item_condition_log (
                                    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                    item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                                    previous_condition VARCHAR(30),
                                    new_condition VARCHAR(30) NOT NULL,
                                    reported_by_user_id UUID REFERENCES users(id),
                                    reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                    notes TEXT
);

-- Item transfers
CREATE TABLE item_transfers (
                                id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                                from_custodian_id UUID REFERENCES organizational_units(id),
                                to_custodian_id UUID REFERENCES organizational_units(id),
                                initiated_by_user_id UUID REFERENCES users(id),
                                approved_by_user_id UUID REFERENCES users(id),
                                notes TEXT,
                                status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'COMPLETED', 'REJECTED')),
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                completed_at TIMESTAMP
);

-- Events
CREATE TABLE events (
                        id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                        tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                        name VARCHAR(200) NOT NULL,
                        type VARCHAR(100) NOT NULL,
                        custom_type_label VARCHAR(100),
                        start_date DATE NOT NULL,
                        end_date DATE NOT NULL,
                        location_id UUID REFERENCES locations(id) ON DELETE SET NULL,
                        organizational_unit_id UUID REFERENCES organizational_units(id),
                        created_by_user_id UUID REFERENCES users(id),
                        status VARCHAR(20) DEFAULT 'PLANNING' CHECK (status IN ('PLANNING', 'ACTIVE', 'WRAP_UP', 'COMPLETED', 'CANCELLED')),
                        inventory_budget_amount DECIMAL(10,2),
                        notes TEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        target_audience VARCHAR(100),
                        CHECK (end_date >= start_date)
);

-- Stovykla details
-- Pastovyklės
CREATE TABLE pastovykles (
                             id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                             event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                             name VARCHAR(100) NOT NULL,
                             responsible_user_id UUID REFERENCES users(id),
                             age_group VARCHAR(30) CHECK (age_group IN ('VILKAI', 'SKAUTAI', 'PATYRE_SKAUTAI', 'VYR_SKAUTAI', 'VYR_SKAUTES', 'MIXED')),
                             notes TEXT
);

-- Pastovyklė inventory
CREATE TABLE pastovykle_members (
                                    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                    pastovykle_id UUID NOT NULL REFERENCES pastovykles(id) ON DELETE CASCADE,
                                    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REMOVED')),
                                    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                    added_by_user_id UUID NOT NULL REFERENCES users(id),
                                    UNIQUE(pastovykle_id, user_id)
);

CREATE TABLE pastovykle_inventory (
                                      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                      pastovykle_id UUID NOT NULL REFERENCES pastovykles(id) ON DELETE CASCADE,
                                      item_id UUID NOT NULL REFERENCES items(id),
                                      distributed_by_user_id UUID REFERENCES users(id),
                                      recipient_user_id UUID REFERENCES users(id),
                                      recipient_type VARCHAR(20) CHECK (recipient_type IN ('DIRECT', 'GURU_PROXY', 'MEMBER')),
                                      quantity_assigned INTEGER NOT NULL CHECK (quantity_assigned > 0),
                                      quantity_returned INTEGER DEFAULT 0,
                                      assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                      returned_at TIMESTAMP,
                                      notes TEXT,
                                      CHECK (quantity_returned <= quantity_assigned)
);

-- Event roles
CREATE TABLE event_roles (
                             id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                             event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                             user_id UUID NOT NULL REFERENCES users(id),
                             role VARCHAR(30) NOT NULL CHECK (role IN (
                                                                       'VIRSININKAS', 'KOMENDANTAS', 'UKVEDYS',
                                                                       'FINANSININKAS',
                                                                       'PASTOVYKLES_GURU', 'VADOVAS', 'SAVANORIS',
                                                                       'PATYRE_SKAUTAS', 'SKAUTAS', 'PROGRAMERIS', 'MAISTININKAS'
                                 )),
                             target_group VARCHAR(20) CHECK (target_group IN ('VILKAI', 'SKAUTAI', 'PATYRE_SKAUTAI', 'VYR_SKAUTAI', 'VYR_SKAUTES', 'SKAUTAI_VILKAI', 'TEVAI', 'PROGRAMA')),
                             pastovykle_id UUID REFERENCES pastovykles(id) ON DELETE CASCADE,
                             assigned_by_user_id UUID REFERENCES users(id),
                             assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Only one VIRSININKAS per event
CREATE UNIQUE INDEX idx_event_roles_one_virsininkas
    ON event_roles(event_id)
    WHERE role = 'VIRSININKAS';
CREATE UNIQUE INDEX idx_event_roles_one_komendantas
    ON event_roles(event_id)
    WHERE role = 'KOMENDANTAS';

-- Event inventory planning buckets
CREATE TABLE event_inventory_buckets (
                                         id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                         event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                                         pastovykle_id UUID REFERENCES pastovykles(id) ON DELETE SET NULL,
                                         location_id UUID REFERENCES locations(id) ON DELETE SET NULL,
                                         name VARCHAR(120) NOT NULL,
                                         type VARCHAR(30) NOT NULL CHECK (type IN ('PROGRAM', 'KITCHEN', 'ADMIN', 'MEDICAL', 'PASTOVYKLE', 'OTHER')),
                                         notes TEXT
);

-- Event inventory fund: existing inventory or missing items that must be bought/borrowed
CREATE TABLE event_inventory_items (
                                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                       event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                                       item_id UUID REFERENCES items(id),
                                       bucket_id UUID REFERENCES event_inventory_buckets(id) ON DELETE SET NULL,
                                       reservation_group_id UUID,
                                       name VARCHAR(200) NOT NULL,
                                       planned_quantity INTEGER NOT NULL CHECK (planned_quantity > 0),
                                       available_quantity INTEGER NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
                                       needs_purchase BOOLEAN NOT NULL DEFAULT FALSE,
                                       notes TEXT,
                                       source_custodian_name VARCHAR(200),
                                       source_location_path VARCHAR(500),
                                       source_temporary_storage_label VARCHAR(255),
                                       source_responsible_user_name VARCHAR(200),
                                       responsible_user_id UUID REFERENCES users(id),
                                       created_by_user_id UUID REFERENCES users(id),
                                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE event_inventory_sources (
                                         id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                         event_inventory_item_id UUID NOT NULL REFERENCES event_inventory_items(id) ON DELETE CASCADE,
                                         item_id UUID REFERENCES items(id),
                                         reservation_group_id UUID,
                                         planned_quantity INTEGER NOT NULL CHECK (planned_quantity > 0),
                                         reserved_quantity INTEGER NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
                                         pickup_custodian_name VARCHAR(200),
                                         pickup_location_path VARCHAR(500),
                                         pickup_temporary_storage_label VARCHAR(255),
                                         pickup_responsible_user_name VARCHAR(200),
                                         pickup_summary VARCHAR(700),
                                         source_status VARCHAR(30) NOT NULL DEFAULT 'PLANNED' CHECK (source_status IN ('PLANNED', 'RESERVED', 'PARTIAL', 'SHORTAGE', 'CANCELLED')),
                                         notes TEXT,
                                         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                         CHECK (reserved_quantity <= planned_quantity)
);

-- Event inventory allocation to program, kitchen, administration, medical, pastovykle or another bucket
CREATE TABLE event_inventory_allocations (
                                             id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                             event_inventory_item_id UUID NOT NULL REFERENCES event_inventory_items(id) ON DELETE CASCADE,
                                             bucket_id UUID NOT NULL REFERENCES event_inventory_buckets(id) ON DELETE CASCADE,
                                             quantity INTEGER NOT NULL CHECK (quantity > 0),
                                             notes TEXT
);

CREATE TABLE event_inventory_custody (
                                         id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                         event_inventory_item_id UUID NOT NULL REFERENCES event_inventory_items(id) ON DELETE CASCADE,
                                         parent_custody_id UUID REFERENCES event_inventory_custody(id) ON DELETE SET NULL,
                                         pastovykle_id UUID REFERENCES pastovykles(id) ON DELETE SET NULL,
                                         holder_user_id UUID REFERENCES users(id),
                                         quantity INTEGER NOT NULL CHECK (quantity > 0),
                                         returned_quantity INTEGER NOT NULL DEFAULT 0 CHECK (returned_quantity >= 0),
                                         status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RETURNED', 'CLOSED')),
                                         created_by_user_id UUID NOT NULL REFERENCES users(id),
                                         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                         closed_at TIMESTAMP,
                                         notes TEXT,
                                         CHECK (returned_quantity <= quantity)
);

CREATE TABLE event_inventory_requests (
                                          id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                          event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                                          event_inventory_item_id UUID NOT NULL REFERENCES event_inventory_items(id) ON DELETE CASCADE,
                                          pastovykle_id UUID REFERENCES pastovykles(id) ON DELETE CASCADE,
                                          target_group VARCHAR(30),
                                          requested_by_user_id UUID NOT NULL REFERENCES users(id),
                                          quantity INTEGER NOT NULL CHECK (quantity > 0),
                                          provider VARCHAR(20) NOT NULL DEFAULT 'UKVEDYS' CHECK (provider IN ('UNIT', 'UKVEDYS')),
                                          due_at TIMESTAMP,
                                          responsible_user_id UUID REFERENCES users(id),
                                          reminder_sent_at TIMESTAMP,
                                          status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'FULFILLED', 'SELF_PROVIDED')),
                                          notes TEXT,
                                          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                          reviewed_by_user_id UUID REFERENCES users(id),
                                          reviewed_at TIMESTAMP,
                                          fulfilled_at TIMESTAMP,
                                          resolved_by_user_id UUID REFERENCES users(id)
);

CREATE TABLE event_inventory_request_history (
                                                   id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                                   request_id UUID NOT NULL REFERENCES event_inventory_requests(id) ON DELETE CASCADE,
                                                   from_provider VARCHAR(20) CHECK (from_provider IS NULL OR from_provider IN ('UNIT', 'UKVEDYS')),
                                                   to_provider VARCHAR(20) NOT NULL CHECK (to_provider IN ('UNIT', 'UKVEDYS')),
                                                   changed_by_user_id UUID NOT NULL REFERENCES users(id),
                                                   notes TEXT,
                                                   created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE senior_unit_access_audit (
                                         id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                         tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                         unit_id UUID NOT NULL REFERENCES organizational_units(id) ON DELETE CASCADE,
                                         actor_user_id UUID NOT NULL REFERENCES users(id),
                                         action VARCHAR(50) NOT NULL,
                                         access_mode VARCHAR(20) NOT NULL CHECK (access_mode IN ('INTERNAL', 'PUBLIC')),
                                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE event_inventory_movements (
                                           id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                           event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                                           event_inventory_item_id UUID NOT NULL REFERENCES event_inventory_items(id) ON DELETE CASCADE,
                                           custody_id UUID REFERENCES event_inventory_custody(id) ON DELETE SET NULL,
                                           inventory_request_id UUID REFERENCES event_inventory_requests(id) ON DELETE SET NULL,
                                           movement_type VARCHAR(30) NOT NULL CHECK (movement_type IN ('PASTOVYKLE_REQUEST', 'ASSIGN_TO_PASTOVYKLE', 'CHECKOUT_TO_PERSON', 'RETURN_TO_PASTOVYKLE', 'RETURN_TO_EVENT_STORAGE', 'TRANSFER', 'RECONCILE_RETURNED', 'RECONCILE_DAMAGED', 'RECONCILE_MISSING', 'RECONCILE_CONSUMED')),
                                           quantity INTEGER NOT NULL CHECK (quantity > 0),
                                           from_pastovykle_id UUID REFERENCES pastovykles(id) ON DELETE SET NULL,
                                           to_pastovykle_id UUID REFERENCES pastovykles(id) ON DELETE SET NULL,
                                           from_user_id UUID REFERENCES users(id),
                                           to_user_id UUID REFERENCES users(id),
                                           performed_by_user_id UUID NOT NULL REFERENCES users(id),
                                           client_request_id VARCHAR(100),
                                           notes TEXT,
                                           created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE event_inventory_transfer_requests (
                                                    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                                    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                                                    source_custody_id UUID NOT NULL REFERENCES event_inventory_custody(id),
                                                    event_inventory_item_id UUID NOT NULL REFERENCES event_inventory_items(id),
                                                    requested_by_user_id UUID NOT NULL REFERENCES users(id),
                                                    requested_from_user_id UUID NOT NULL REFERENCES users(id),
                                                    quantity INTEGER NOT NULL CHECK (quantity > 0),
                                                    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
                                                    notes TEXT,
                                                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                    responded_at TIMESTAMP,
                                                    responded_by_user_id UUID REFERENCES users(id),
                                                    movement_id UUID REFERENCES event_inventory_movements(id)
);

-- Event purchases: one receipt/invoice can cover multiple missing event inventory lines
CREATE TABLE event_purchases (
                                 id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                 event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                                 purchased_by_user_id UUID REFERENCES users(id),
                                 status VARCHAR(30) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PURCHASED', 'ADDED_TO_INVENTORY', 'CANCELLED')),
                                 purchase_date DATE,
                                 total_amount DECIMAL(10, 2),
                                 invoice_file_url TEXT,
                                 notes TEXT,
                                 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                 updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER update_event_purchases_updated_at
    BEFORE UPDATE ON event_purchases
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE event_purchase_invoices (
                                      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                      purchase_id UUID NOT NULL REFERENCES event_purchases(id) ON DELETE CASCADE,
                                      file_url TEXT NOT NULL,
                                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE event_extra_costs (
                                   id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                   event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                                   category VARCHAR(40) NOT NULL CHECK (category IN ('FIREWOOD', 'TOILETS', 'OTHER')),
                                   label VARCHAR(200) NOT NULL,
                                   quantity DECIMAL(10,2),
                                   unit VARCHAR(40),
                                   unit_price DECIMAL(10,2),
                                   total_amount DECIMAL(10,2) NOT NULL,
                                   notes TEXT,
                                   created_by_user_id UUID REFERENCES users(id),
                                   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE event_purchase_items (
                                      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                      purchase_id UUID NOT NULL REFERENCES event_purchases(id) ON DELETE CASCADE,
                                      event_inventory_item_id UUID NOT NULL REFERENCES event_inventory_items(id) ON DELETE CASCADE,
                                      purchased_quantity INTEGER NOT NULL CHECK (purchased_quantity > 0),
                                      unit_price DECIMAL(10, 2),
                                      added_to_inventory_item_id UUID REFERENCES items(id),
                                      added_to_inventory BOOLEAN NOT NULL DEFAULT FALSE,
                                      notes TEXT
);

-- Draugovė requisitions
CREATE TABLE event_purchase_item_reconciliations (
                                                     id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                                     purchase_item_id UUID NOT NULL REFERENCES event_purchase_items(id) ON DELETE CASCADE,
                                                     decision VARCHAR(40) NOT NULL CHECK (decision IN ('ADD_NEW_ITEM', 'INCREASE_EXISTING_ITEM', 'CONSUMED', 'IGNORE')),
                                                     quantity INTEGER NOT NULL CHECK (quantity > 0),
                                                     added_inventory_item_id UUID REFERENCES items(id),
                                                     performed_by_user_id UUID NOT NULL REFERENCES users(id),
                                                     notes TEXT,
                                                     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE draugove_requisitions (
                                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                       tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                       organizational_unit_id UUID REFERENCES organizational_units(id),
                                       event_id UUID REFERENCES events(id),
                                       created_by_user_id UUID NOT NULL REFERENCES users(id),
                                       reviewed_by_user_id UUID REFERENCES users(id),
                                       status VARCHAR(30) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED', 'PARTIALLY_APPROVED', 'APPROVED', 'PURCHASED', 'INVENTORY_ADDED', 'REJECTED', 'CANCELLED')),
                                       unit_review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (unit_review_status IN ('PENDING', 'APPROVED', 'FORWARDED', 'REJECTED', 'SKIPPED', 'CANCELLED')),
                                       unit_reviewed_by_user_id UUID REFERENCES users(id),
                                       unit_reviewed_at TIMESTAMP,
                                       top_level_review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED' CHECK (top_level_review_status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
                                       top_level_reviewed_by_user_id UUID REFERENCES users(id),
                                       top_level_reviewed_at TIMESTAMP,
                                       purchased_at TIMESTAMP,
                                       purchased_by_user_id UUID REFERENCES users(id),
                                       added_to_inventory_at TIMESTAMP,
                                       added_to_inventory_by_user_id UUID REFERENCES users(id),
                                       notes TEXT,
                                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER update_draugove_requisitions_updated_at
    BEFORE UPDATE ON draugove_requisitions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Draugovė requisition items
CREATE TABLE draugove_requisition_items (
                                            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                            requisition_id UUID NOT NULL REFERENCES draugove_requisitions(id) ON DELETE CASCADE,
                                            item_id UUID REFERENCES items(id),
                                            request_type VARCHAR(30) NOT NULL DEFAULT 'NEW_ITEM' CHECK (request_type IN ('NEW_ITEM', 'RESTOCK_EXISTING')),
                                            existing_item_id UUID REFERENCES items(id),
                                            item_name VARCHAR(200),
                                            item_description TEXT,
                                            quantity_requested INTEGER NOT NULL CHECK (quantity_requested > 0),
                                            quantity_approved INTEGER CHECK (quantity_approved >= 0),
                                            rejection_reason TEXT,
                                            notes TEXT,
                                            CHECK (item_id IS NOT NULL OR item_name IS NOT NULL),
                                            CHECK (quantity_approved <= quantity_requested),
                                            CHECK ((request_type = 'RESTOCK_EXISTING' AND existing_item_id IS NOT NULL) OR (request_type = 'NEW_ITEM' AND existing_item_id IS NULL))
);

CREATE TABLE item_history (
                              id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                              item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                              event_type VARCHAR(40) NOT NULL,
                              quantity_change INTEGER,
                              performed_by_user_id UUID REFERENCES users(id),
                              requisition_id UUID REFERENCES draugove_requisitions(id),
                              notes TEXT,
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Reservations
CREATE TABLE reservations (
                              id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                              group_id UUID NOT NULL,
                              title VARCHAR(200) NOT NULL,
                              item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                              tuntas_id UUID NOT NULL REFERENCES tuntai(id),
                              reserved_by_user_id UUID NOT NULL REFERENCES users(id),
                              approved_by_user_id UUID REFERENCES users(id),
                              requesting_unit_id UUID REFERENCES organizational_units(id),
                              event_id UUID REFERENCES events(id),
                              quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
                              start_date DATE NOT NULL,
                              end_date DATE NOT NULL,
                              unit_review_status VARCHAR(20) DEFAULT 'NOT_REQUIRED' CHECK (unit_review_status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED')),
                              unit_reviewed_by_user_id UUID REFERENCES users(id),
                              unit_reviewed_at TIMESTAMP,
                              top_level_review_status VARCHAR(20) DEFAULT 'NOT_REQUIRED' CHECK (top_level_review_status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED')),
                              top_level_reviewed_by_user_id UUID REFERENCES users(id),
                              top_level_reviewed_at TIMESTAMP,
                              pickup_at TIMESTAMP,
                              pickup_location_id UUID REFERENCES locations(id) ON DELETE SET NULL,
                              pickup_proposal_status VARCHAR(20) DEFAULT 'NONE' CHECK (pickup_proposal_status IN ('NONE', 'PENDING', 'ACCEPTED')),
                              pickup_proposed_at TIMESTAMP,
                              pickup_proposed_by_user_id UUID REFERENCES users(id),
                              pickup_responded_at TIMESTAMP,
                              pickup_responded_by_user_id UUID REFERENCES users(id),
                              return_at TIMESTAMP,
                              return_location_id UUID REFERENCES locations(id) ON DELETE SET NULL,
                              return_proposal_status VARCHAR(20) DEFAULT 'NONE' CHECK (return_proposal_status IN ('NONE', 'PENDING', 'ACCEPTED')),
                              return_proposed_at TIMESTAMP,
                              return_proposed_by_user_id UUID REFERENCES users(id),
                              return_responded_at TIMESTAMP,
                              return_responded_by_user_id UUID REFERENCES users(id),
                              status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'ACTIVE', 'RETURNED', 'CANCELLED', 'REJECTED')),
                              notes TEXT,
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                              CHECK (end_date >= start_date)
);

CREATE TRIGGER update_reservations_updated_at
    BEFORE UPDATE ON reservations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE reservation_movements (
                                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                       reservation_group_id UUID NOT NULL,
                                       item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                                       location_id UUID REFERENCES locations(id) ON DELETE SET NULL,
                                       type VARCHAR(20) NOT NULL CHECK (type IN ('ISSUE', 'RETURN_MARKED', 'RETURN')),
                                       quantity INTEGER NOT NULL CHECK (quantity > 0),
                                       performed_by_user_id UUID NOT NULL REFERENCES users(id),
                                       notes TEXT,
                                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Return reminders
CREATE TABLE return_reminders (
                                  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                  reservation_id UUID NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
                                  reminder_date DATE NOT NULL,
                                  sent_at TIMESTAMP,
                                  acknowledged_at TIMESTAMP
);

-- Inventory list templates
CREATE TABLE inventory_list_templates (
                                          id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                          tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                          name VARCHAR(200) NOT NULL,
                                          event_type VARCHAR(100),
                                          created_by_user_id UUID REFERENCES users(id),
                                          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Inventory list template items
CREATE TABLE inventory_list_template_items (
                                               id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                               template_id UUID NOT NULL REFERENCES inventory_list_templates(id) ON DELETE CASCADE,
                                               item_id UUID REFERENCES items(id),
                                               item_name VARCHAR(200) NOT NULL,
                                               quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
                                               category VARCHAR(100),
                                               notes TEXT
);

-- Inventory kits
CREATE TABLE inventory_kits (
                                id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                custodian_id UUID REFERENCES organizational_units(id),
                                name VARCHAR(200) NOT NULL,
                                description TEXT,
                                location_id UUID REFERENCES locations(id),
                                temporary_storage_label VARCHAR(255),
                                responsible_user_id UUID REFERENCES users(id),
                                created_by_user_id UUID REFERENCES users(id),
                                status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inventory_kit_items (
                                     id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                     kit_id UUID NOT NULL REFERENCES inventory_kits(id) ON DELETE CASCADE,
                                     item_id UUID NOT NULL REFERENCES items(id),
                                     quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
                                     notes TEXT
);

CREATE INDEX idx_inventory_kits_tuntas_status ON inventory_kits(tuntas_id, status);
CREATE INDEX idx_inventory_kits_custodian ON inventory_kits(custodian_id);
CREATE INDEX idx_inventory_kits_location ON inventory_kits(location_id);
CREATE INDEX idx_inventory_kits_responsible_user ON inventory_kits(responsible_user_id);
CREATE INDEX idx_inventory_kit_items_kit ON inventory_kit_items(kit_id);
CREATE UNIQUE INDEX idx_inventory_kit_items_item ON inventory_kit_items(item_id);

-- Import column mappings
CREATE TABLE import_column_mappings (
                                        id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                        tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                        name VARCHAR(100) NOT NULL,
                                        created_by_user_id UUID REFERENCES users(id),
                                        column_mappings JSONB NOT NULL,
                                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Export logs
CREATE TABLE export_logs (
                             id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                             event_id UUID REFERENCES events(id),
                             tuntas_id UUID NOT NULL REFERENCES tuntai(id),
                             exported_by_user_id UUID REFERENCES users(id),
                             export_format VARCHAR(10) CHECK (export_format IN ('XLSX', 'CSV')),
                             exported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Devices
CREATE TABLE devices (
                         id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                         user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                         device_name VARCHAR(100),
                         device_token TEXT UNIQUE NOT NULL,
                         last_sync_at TIMESTAMP,
                         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Sync operations
CREATE TABLE sync_operations (
                                 id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                 operation_id UUID UNIQUE NOT NULL,
                                 operation_type VARCHAR(10) NOT NULL CHECK (operation_type IN ('CREATE', 'UPDATE', 'DELETE')),
                                 entity_type VARCHAR(50) NOT NULL,
                                 entity_id UUID NOT NULL,
                                 payload JSONB NOT NULL,
                                 user_id UUID REFERENCES users(id),
                                 device_id UUID REFERENCES devices(id),
                                 client_timestamp TIMESTAMP NOT NULL,
                                 server_timestamp TIMESTAMP,
                                 status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPLIED', 'CONFLICT', 'REJECTED')),
                                 conflict_notes TEXT
);

-- Invitations
CREATE TABLE invitations (
                             id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                             tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                             code VARCHAR(20) UNIQUE NOT NULL,
                             role_id UUID NOT NULL REFERENCES roles(id),
                             organizational_unit_id UUID REFERENCES organizational_units(id),
                             created_by_user_id UUID NOT NULL REFERENCES users(id),
                             used_by_user_id UUID REFERENCES users(id),
                             expires_at TIMESTAMP NOT NULL,
                             used_at TIMESTAMP,
                             created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE bendras_inventory_requests (
                                            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                            tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                            requested_by_user_id UUID NOT NULL REFERENCES users(id),
                                            item_id UUID REFERENCES items(id),
                                            item_description TEXT,
                                            quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
                                            event_id UUID REFERENCES events(id),
                                            requesting_unit_id UUID REFERENCES organizational_units(id),
                                            needs_draugininkas_approval BOOLEAN NOT NULL DEFAULT FALSE,
                                            draugininkas_status VARCHAR(20) CHECK (draugininkas_status IN ('PENDING', 'FORWARDED', 'REJECTED')),
                                            draugininkas_reviewed_by_user_id UUID REFERENCES users(id),
                                            draugininkas_rejection_reason TEXT,
                                            top_level_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (top_level_status IN ('PENDING', 'APPROVED', 'REJECTED')),
                                            top_level_reviewed_by_user_id UUID REFERENCES users(id),
                                            top_level_rejection_reason TEXT,
                                            start_date DATE,
                                            end_date DATE,
                                            notes TEXT,
                                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                            CHECK (item_id IS NOT NULL OR item_description IS NOT NULL)
);

CREATE TRIGGER update_bendras_inventory_requests_updated_at
    BEFORE UPDATE ON bendras_inventory_requests
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TABLE bendras_inventory_request_items (
                                                 id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                                 request_id UUID NOT NULL REFERENCES bendras_inventory_requests(id) ON DELETE CASCADE,
                                                 item_id UUID NOT NULL REFERENCES items(id),
                                                 quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0)
);

-- Unit assignments
CREATE TABLE unit_assignments (
                                  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                  user_id UUID NOT NULL REFERENCES users(id),
                                  organizational_unit_id UUID NOT NULL REFERENCES organizational_units(id) ON DELETE CASCADE,
                                  tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                  assignment_type VARCHAR(30) NOT NULL DEFAULT 'MEMBER',
                                  is_publicly_visible BOOLEAN NOT NULL DEFAULT FALSE,
                                  assigned_by_user_id UUID REFERENCES users(id),
                                  joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                  left_at TIMESTAMP
);

CREATE TABLE item_check_sessions (
                                     id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                     tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                     context_type VARCHAR(30) NOT NULL CHECK (context_type IN ('EVENT_RETURN', 'STORAGE_AUDIT')),
                                     event_id UUID REFERENCES events(id) ON DELETE CASCADE,
                                     scope_custodian_id UUID REFERENCES organizational_units(id) ON DELETE SET NULL,
                                     scope_type VARCHAR(100),
                                     scope_category VARCHAR(100),
                                     scope_shared_only BOOLEAN NOT NULL DEFAULT FALSE,
    scope_personal_owner_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    started_by_user_id UUID NOT NULL REFERENCES users(id),
    completed_by_user_id UUID REFERENCES users(id),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'COMPLETED')),
    scope_item_count INTEGER NOT NULL DEFAULT 0,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE TABLE item_checks (
                             id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                             session_id UUID NOT NULL REFERENCES item_check_sessions(id) ON DELETE CASCADE,
                             item_id UUID REFERENCES items(id) ON DELETE SET NULL,
                             event_inventory_item_id UUID REFERENCES event_inventory_items(id) ON DELETE SET NULL,
                             custody_id UUID REFERENCES event_inventory_custody(id) ON DELETE SET NULL,
                             result VARCHAR(20) NOT NULL CHECK (result IN ('FOUND', 'MISSING', 'MISPLACED', 'DAMAGED', 'CONSUMED', 'RETURNED')),
                             quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
                             expected_quantity INTEGER NOT NULL DEFAULT 1 CHECK (expected_quantity >= 0),
                             actual_quantity INTEGER NOT NULL DEFAULT 1 CHECK (actual_quantity >= 0),
                             actual_location_id UUID REFERENCES locations(id) ON DELETE SET NULL,
                             actual_location_note VARCHAR(255),
                             condition_at_check VARCHAR(30),
                             checked_by_user_id UUID NOT NULL REFERENCES users(id),
                             notes TEXT,
                             checked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Legacy memberships table (kept for backward compatibility)
-- Indexes
CREATE INDEX idx_items_tuntas ON items(tuntas_id);
CREATE INDEX idx_items_custodian ON items(custodian_id);
CREATE INDEX idx_items_status ON items(status);
CREATE INDEX idx_items_tuntas_status_custodian_type ON items(tuntas_id, status, custodian_id, type);
CREATE INDEX idx_items_tuntas_updated_at ON items(tuntas_id, updated_at);
CREATE INDEX idx_items_source_shared_status ON items(source_shared_item_id, status);
CREATE INDEX idx_items_duplicate_lookup ON items(tuntas_id, status, type, category, custodian_id, lower(name), updated_at);
CREATE UNIQUE INDEX idx_items_qr_token ON items(qr_token);
CREATE INDEX idx_reservations_item ON reservations(item_id);
CREATE INDEX idx_reservations_group ON reservations(group_id);
CREATE INDEX idx_reservations_dates ON reservations(start_date, end_date);
CREATE INDEX idx_reservations_status ON reservations(status);
CREATE INDEX idx_reservations_tuntas_status_group ON reservations(tuntas_id, status, group_id);
CREATE INDEX idx_reservations_tuntas_user_status ON reservations(tuntas_id, reserved_by_user_id, status);
CREATE INDEX idx_reservations_tuntas_unit_status ON reservations(tuntas_id, requesting_unit_id, status);
CREATE INDEX idx_reservation_movements_group ON reservation_movements(reservation_group_id);
CREATE INDEX idx_reservation_movements_item ON reservation_movements(item_id);
CREATE INDEX idx_reservation_movements_location ON reservation_movements(location_id);
CREATE INDEX idx_reservation_movements_type ON reservation_movements(type);
CREATE INDEX idx_reservation_movements_group_item_type ON reservation_movements(reservation_group_id, item_id, type);
CREATE INDEX idx_locations_tuntas ON locations(tuntas_id);
CREATE INDEX idx_locations_parent ON locations(parent_location_id);
CREATE INDEX idx_locations_visibility ON locations(visibility);
CREATE INDEX idx_sync_operations_status ON sync_operations(status);
CREATE INDEX idx_sync_operations_device ON sync_operations(device_id);
CREATE INDEX idx_user_leadership_roles_user ON user_leadership_roles(user_id);
CREATE INDEX idx_user_leadership_roles_tuntas ON user_leadership_roles(tuntas_id);
CREATE INDEX idx_user_leadership_roles_user_tuntas ON user_leadership_roles(user_id, tuntas_id);
CREATE INDEX idx_leadership_change_requests_tuntas_status ON leadership_change_requests(tuntas_id, status);
CREATE INDEX idx_leadership_change_requests_assignment_pending ON leadership_change_requests(role_assignment_id) WHERE status = 'PENDING';
CREATE INDEX idx_user_ranks_user ON user_ranks(user_id);
CREATE INDEX idx_user_ranks_tuntas ON user_ranks(tuntas_id);
CREATE INDEX idx_user_ranks_user_tuntas ON user_ranks(user_id, tuntas_id);
CREATE INDEX idx_events_tuntas ON events(tuntas_id);
CREATE INDEX idx_events_dates ON events(start_date, end_date);
CREATE INDEX idx_events_tuntas_status_start ON events(tuntas_id, status, start_date);
CREATE INDEX idx_event_roles_user_role_event ON event_roles(user_id, role, event_id);
CREATE INDEX idx_event_inventory_buckets_event ON event_inventory_buckets(event_id);
CREATE INDEX idx_event_inventory_items_event ON event_inventory_items(event_id);
CREATE INDEX idx_event_inventory_items_event_purchase ON event_inventory_items(event_id, needs_purchase);
CREATE INDEX idx_event_inventory_sources_item ON event_inventory_sources(event_inventory_item_id);
CREATE INDEX idx_event_inventory_allocations_item ON event_inventory_allocations(event_inventory_item_id);
CREATE INDEX idx_event_inventory_allocations_bucket ON event_inventory_allocations(bucket_id);
CREATE INDEX idx_event_inventory_custody_parent ON event_inventory_custody(parent_custody_id);
CREATE INDEX idx_event_inventory_requests_event ON event_inventory_requests(event_id);
CREATE INDEX idx_event_inventory_requests_provider_status ON event_inventory_requests(event_id, provider, status, created_at);
CREATE UNIQUE INDEX idx_event_inventory_movements_client_request ON event_inventory_movements(event_id, client_request_id) WHERE client_request_id IS NOT NULL;
CREATE INDEX idx_event_transfer_requests_event_status ON event_inventory_transfer_requests(event_id, status, created_at);
CREATE INDEX idx_event_transfer_requests_from_user ON event_inventory_transfer_requests(requested_from_user_id, status, created_at);
CREATE INDEX idx_event_transfer_requests_requester ON event_inventory_transfer_requests(requested_by_user_id, status, created_at);
CREATE UNIQUE INDEX idx_event_transfer_requests_pending_unique ON event_inventory_transfer_requests(source_custody_id, requested_by_user_id) WHERE status = 'PENDING';
CREATE INDEX idx_event_purchases_event ON event_purchases(event_id);
CREATE INDEX idx_event_purchase_invoices_purchase_id ON event_purchase_invoices(purchase_id);
CREATE INDEX idx_event_extra_costs_event ON event_extra_costs(event_id);
CREATE INDEX idx_event_purchase_items_purchase ON event_purchase_items(purchase_id);
CREATE INDEX idx_event_purchase_items_inventory_item ON event_purchase_items(event_inventory_item_id);
CREATE INDEX idx_event_purchase_item_reconciliations_item ON event_purchase_item_reconciliations(purchase_item_id);
CREATE INDEX idx_item_assignments_item ON item_assignments(item_id);
CREATE INDEX idx_item_assignments_user ON item_assignments(assigned_to_user_id);
CREATE INDEX idx_direct_item_loans_item_status ON direct_item_loans(item_id, status);
CREATE INDEX idx_direct_item_loans_tuntas_user ON direct_item_loans(tuntas_id, issued_to_user_id);
CREATE INDEX idx_item_custom_fields_item_field ON item_custom_fields(item_id, field_name);
CREATE INDEX idx_inventory_kit_items_item ON inventory_kit_items(item_id);
CREATE INDEX idx_inventory_kits_status ON inventory_kits(status);
CREATE INDEX idx_draugove_requisitions_tuntas ON draugove_requisitions(tuntas_id);
CREATE INDEX idx_draugove_requisitions_unit ON draugove_requisitions(organizational_unit_id);
CREATE INDEX idx_draugove_requisitions_tuntas_review ON draugove_requisitions(tuntas_id, top_level_review_status, unit_review_status);
CREATE INDEX idx_draugove_requisitions_tuntas_unit_user ON draugove_requisitions(tuntas_id, organizational_unit_id, created_by_user_id);
CREATE INDEX idx_draugove_requisition_items_requisition ON draugove_requisition_items(requisition_id);
CREATE INDEX idx_draugove_requisitions_tuntas_updated_at ON draugove_requisitions(tuntas_id, updated_at);
CREATE INDEX idx_pastovykle_inventory_pastovykle ON pastovykle_inventory(pastovykle_id);
CREATE INDEX idx_sync_operations_client_timestamp ON sync_operations(client_timestamp);
CREATE INDEX idx_invitations_code ON invitations(code);
CREATE INDEX idx_invitations_tuntas ON invitations(tuntas_id);
CREATE INDEX idx_bendras_inventory_requests_tuntas ON bendras_inventory_requests(tuntas_id);
CREATE INDEX idx_bendras_inventory_requests_user ON bendras_inventory_requests(requested_by_user_id);
CREATE INDEX idx_bendras_inventory_requests_item ON bendras_inventory_requests(item_id);
CREATE INDEX idx_bendras_inventory_requests_top_status ON bendras_inventory_requests(top_level_status);
CREATE INDEX idx_bendras_requests_tuntas_status_unit ON bendras_inventory_requests(tuntas_id, top_level_status, requesting_unit_id);
CREATE INDEX idx_bendras_inventory_request_items_request ON bendras_inventory_request_items(request_id);
CREATE INDEX idx_bendras_inventory_requests_tuntas_updated_at ON bendras_inventory_requests(tuntas_id, updated_at);
CREATE INDEX idx_unit_assignments_user ON unit_assignments(user_id);
CREATE INDEX idx_unit_assignments_tuntas ON unit_assignments(tuntas_id);
CREATE INDEX idx_unit_assignments_unit ON unit_assignments(organizational_unit_id);
CREATE INDEX idx_reservations_tuntas_updated_at ON reservations(tuntas_id, updated_at);
CREATE INDEX idx_events_tuntas_updated_at ON events(tuntas_id, updated_at);
CREATE INDEX idx_user_tuntas_memberships_active_user_tuntas ON user_tuntas_memberships(user_id, tuntas_id) WHERE left_at IS NULL;
CREATE INDEX idx_user_leadership_roles_active_user_tuntas ON user_leadership_roles(user_id, tuntas_id, role_id, organizational_unit_id) WHERE term_status = 'ACTIVE' AND left_at IS NULL;
CREATE INDEX idx_unit_assignments_active_user_tuntas ON unit_assignments(user_id, tuntas_id, organizational_unit_id) WHERE left_at IS NULL;
CREATE INDEX idx_unit_assignments_public_visibility ON unit_assignments(organizational_unit_id, is_publicly_visible) WHERE left_at IS NULL;
CREATE INDEX idx_item_check_sessions_tuntas_context ON item_check_sessions(tuntas_id, context_type);
CREATE INDEX idx_item_check_sessions_event ON item_check_sessions(event_id);
CREATE INDEX idx_item_check_sessions_tuntas_status_scope ON item_check_sessions(tuntas_id, status, scope_custodian_id);
CREATE INDEX idx_item_checks_session ON item_checks(session_id);
CREATE INDEX idx_item_checks_item ON item_checks(item_id);
CREATE INDEX idx_item_checks_custody ON item_checks(custody_id);

-- Revocable, rotating authentication sessions and shared login throttling.
CREATE TABLE auth_refresh_sessions (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(20) NOT NULL CHECK (subject_type IN ('user', 'super_admin')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    replaced_by_session_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP
);

CREATE INDEX idx_auth_refresh_sessions_subject_active
    ON auth_refresh_sessions(subject_id, subject_type, expires_at)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_auth_refresh_sessions_expiry ON auth_refresh_sessions(expires_at);

CREATE TABLE auth_login_throttles (
    key VARCHAR(320) PRIMARY KEY,
    failed_count INTEGER NOT NULL DEFAULT 0,
    window_started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    blocked_until TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_auth_login_throttles_cleanup
    ON auth_login_throttles(updated_at, blocked_until);

CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    subject_id UUID NOT NULL,
    subject_type VARCHAR(20) NOT NULL CHECK (subject_type IN ('user', 'super_admin')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_password_reset_tokens_subject
    ON password_reset_tokens(subject_id, subject_type, created_at DESC);
CREATE INDEX idx_password_reset_tokens_expiry ON password_reset_tokens(expires_at);
