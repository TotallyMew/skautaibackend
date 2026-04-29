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
                        status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'REJECTED')),
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
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

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

-- User ranks
CREATE TABLE user_ranks (
                            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
                            tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                            assigned_by_user_id UUID REFERENCES users(id),
                            assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
                       category VARCHAR(30) NOT NULL CHECK (category IN ('CAMPING', 'TOOLS', 'COOKING', 'FIRST_AID', 'UNIFORMS', 'BOOKS', 'PERSONAL_LOANS')),
                       condition VARCHAR(20) DEFAULT 'GOOD' CHECK (condition IN ('GOOD', 'DAMAGED', 'WRITTEN_OFF')),
                       quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
                       location_id UUID REFERENCES locations(id) ON DELETE SET NULL,
                       temporary_storage_label VARCHAR(255),
                       source_shared_item_id UUID REFERENCES items(id),
                       responsible_user_id UUID REFERENCES users(id),
                       created_by_user_id UUID REFERENCES users(id),
                       photo_url TEXT,
                       purchase_date DATE,
                       purchase_price DECIMAL(10,2),
                       notes TEXT,
                       status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PENDING_APPROVAL', 'INACTIVE')),
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER update_items_updated_at
    BEFORE UPDATE ON items
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Item addition requests
CREATE TABLE item_addition_requests (
                                        id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                        tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                        requested_by_user_id UUID NOT NULL REFERENCES users(id),
                                        reviewed_by_user_id UUID REFERENCES users(id),
                                        target_owner_type VARCHAR(20) NOT NULL CHECK (target_owner_type IN ('TUNTAS', 'DRAUGOVE')),
                                        target_owner_id UUID NOT NULL,
                                        item_name VARCHAR(200) NOT NULL,
                                        description TEXT,
                                        quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
                                        category VARCHAR(20),
                                        status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
                                        rejection_reason TEXT,
                                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                        reviewed_at TIMESTAMP
);

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

-- Item condition log
CREATE TABLE item_condition_log (
                                    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                    item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                                    previous_condition VARCHAR(20),
                                    new_condition VARCHAR(20) NOT NULL,
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
                        type VARCHAR(20) NOT NULL CHECK (type IN ('STOVYKLA', 'SUEIGA', 'RENGINYS')),
                        start_date DATE NOT NULL,
                        end_date DATE NOT NULL,
                        location_id UUID REFERENCES locations(id) ON DELETE SET NULL,
                        organizational_unit_id UUID REFERENCES organizational_units(id),
                        created_by_user_id UUID REFERENCES users(id),
                        status VARCHAR(20) DEFAULT 'PLANNING' CHECK (status IN ('PLANNING', 'ACTIVE', 'WRAP_UP', 'COMPLETED', 'CANCELLED')),
                        notes TEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
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
CREATE TABLE pastovykle_inventory (
                                      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                      pastovykle_id UUID NOT NULL REFERENCES pastovykles(id) ON DELETE CASCADE,
                                      item_id UUID NOT NULL REFERENCES items(id),
                                      distributed_by_user_id UUID REFERENCES users(id),
                                      recipient_user_id UUID REFERENCES users(id),
                                      recipient_type VARCHAR(20) CHECK (recipient_type IN ('DIRECT', 'GURU_PROXY')),
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
                                                                       'PASTOVYKLES_GURU', 'VADOVAS', 'SAVANORIS',
                                                                       'PATYRE_SKAUTAS', 'SKAUTAS', 'PROGRAMERIS', 'MAISTININKAS'
                                 )),
                             target_group VARCHAR(20) CHECK (target_group IN ('PATYRE_SKAUTAI', 'SKAUTAI_VILKAI', 'TEVAI')),
                             assigned_by_user_id UUID REFERENCES users(id),
                             assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                             UNIQUE(event_id, user_id, role)
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
                                         responsible_user_id UUID REFERENCES users(id),
                                         created_by_user_id UUID REFERENCES users(id),
                                         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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
                                           pastovykle_id UUID NOT NULL REFERENCES pastovykles(id) ON DELETE CASCADE,
                                           requested_by_user_id UUID NOT NULL REFERENCES users(id),
                                           quantity INTEGER NOT NULL CHECK (quantity > 0),
                                           status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'FULFILLED', 'SELF_PROVIDED')),
                                           notes TEXT,
                                           created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                           reviewed_by_user_id UUID REFERENCES users(id),
                                           reviewed_at TIMESTAMP,
                                           fulfilled_at TIMESTAMP,
                                           resolved_by_user_id UUID REFERENCES users(id)
);

CREATE TABLE event_inventory_movements (
                                            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                            event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                                            event_inventory_item_id UUID NOT NULL REFERENCES event_inventory_items(id) ON DELETE CASCADE,
                                            custody_id UUID REFERENCES event_inventory_custody(id) ON DELETE SET NULL,
                                            inventory_request_id UUID REFERENCES event_inventory_requests(id) ON DELETE SET NULL,
                                            movement_type VARCHAR(30) NOT NULL CHECK (movement_type IN ('PASTOVYKLE_REQUEST', 'ASSIGN_TO_PASTOVYKLE', 'CHECKOUT_TO_PERSON', 'RETURN_TO_PASTOVYKLE', 'RETURN_TO_EVENT_STORAGE', 'TRANSFER')),
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
CREATE TABLE draugove_requisitions (
                                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                       tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                       organizational_unit_id UUID REFERENCES organizational_units(id),
                                       event_id UUID REFERENCES events(id),
                                       created_by_user_id UUID NOT NULL REFERENCES users(id),
                                       reviewed_by_user_id UUID REFERENCES users(id),
                                       status VARCHAR(30) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED', 'PARTIALLY_APPROVED', 'APPROVED', 'REJECTED')),
                                       unit_review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (unit_review_status IN ('PENDING', 'APPROVED', 'FORWARDED', 'REJECTED', 'SKIPPED')),
                                       unit_reviewed_by_user_id UUID REFERENCES users(id),
                                       unit_reviewed_at TIMESTAMP,
                                       top_level_review_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED' CHECK (top_level_review_status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED')),
                                       top_level_reviewed_by_user_id UUID REFERENCES users(id),
                                       top_level_reviewed_at TIMESTAMP,
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
                                            item_name VARCHAR(200),
                                            item_description TEXT,
                                            quantity_requested INTEGER NOT NULL CHECK (quantity_requested > 0),
                                            quantity_approved INTEGER CHECK (quantity_approved >= 0),
                                            rejection_reason TEXT,
                                            notes TEXT,
                                            CHECK (item_id IS NOT NULL OR item_name IS NOT NULL),
                                            CHECK (quantity_approved <= quantity_requested)
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
                                          event_type VARCHAR(20) CHECK (event_type IN ('STOVYKLA', 'SUEIGA', 'RENGINYS')),
                                          created_by_user_id UUID REFERENCES users(id),
                                          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Inventory list template items
CREATE TABLE inventory_list_template_items (
                                               id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                               template_id UUID NOT NULL REFERENCES inventory_list_templates(id) ON DELETE CASCADE,
                                               item_name VARCHAR(200) NOT NULL,
                                               quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
                                               category VARCHAR(20),
                                               notes TEXT
);

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
                                  assigned_by_user_id UUID REFERENCES users(id),
                                  joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                  left_at TIMESTAMP
);

-- Legacy memberships table (kept for backward compatibility)
CREATE TABLE user_draugove_memberships (
                                           id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                           user_id UUID NOT NULL REFERENCES users(id),
                                           organizational_unit_id UUID NOT NULL REFERENCES organizational_units(id) ON DELETE CASCADE,
                                           tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                           is_lent BOOLEAN NOT NULL DEFAULT FALSE,
                                           assigned_by_user_id UUID REFERENCES users(id),
                                           joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                           left_at TIMESTAMP
);

-- Indexes
CREATE INDEX idx_items_tuntas ON items(tuntas_id);
CREATE INDEX idx_items_custodian ON items(custodian_id);
CREATE INDEX idx_items_status ON items(status);
CREATE INDEX idx_reservations_item ON reservations(item_id);
CREATE INDEX idx_reservations_group ON reservations(group_id);
CREATE INDEX idx_reservations_dates ON reservations(start_date, end_date);
CREATE INDEX idx_reservations_status ON reservations(status);
CREATE INDEX idx_reservation_movements_group ON reservation_movements(reservation_group_id);
CREATE INDEX idx_reservation_movements_item ON reservation_movements(item_id);
CREATE INDEX idx_reservation_movements_location ON reservation_movements(location_id);
CREATE INDEX idx_reservation_movements_type ON reservation_movements(type);
CREATE INDEX idx_locations_tuntas ON locations(tuntas_id);
CREATE INDEX idx_locations_parent ON locations(parent_location_id);
CREATE INDEX idx_locations_visibility ON locations(visibility);
CREATE INDEX idx_sync_operations_status ON sync_operations(status);
CREATE INDEX idx_sync_operations_device ON sync_operations(device_id);
CREATE INDEX idx_user_leadership_roles_user ON user_leadership_roles(user_id);
CREATE INDEX idx_user_leadership_roles_tuntas ON user_leadership_roles(tuntas_id);
CREATE INDEX idx_user_ranks_user ON user_ranks(user_id);
CREATE INDEX idx_user_ranks_tuntas ON user_ranks(tuntas_id);
CREATE INDEX idx_events_tuntas ON events(tuntas_id);
CREATE INDEX idx_events_dates ON events(start_date, end_date);
CREATE INDEX idx_event_inventory_buckets_event ON event_inventory_buckets(event_id);
CREATE INDEX idx_event_inventory_items_event ON event_inventory_items(event_id);
CREATE INDEX idx_event_inventory_allocations_item ON event_inventory_allocations(event_inventory_item_id);
CREATE INDEX idx_event_inventory_allocations_bucket ON event_inventory_allocations(bucket_id);
CREATE INDEX idx_event_inventory_custody_parent ON event_inventory_custody(parent_custody_id);
CREATE INDEX idx_event_inventory_requests_event ON event_inventory_requests(event_id);
CREATE UNIQUE INDEX idx_event_inventory_movements_client_request ON event_inventory_movements(event_id, client_request_id) WHERE client_request_id IS NOT NULL;
CREATE INDEX idx_event_purchases_event ON event_purchases(event_id);
CREATE INDEX idx_event_purchase_items_purchase ON event_purchase_items(purchase_id);
CREATE INDEX idx_event_purchase_items_inventory_item ON event_purchase_items(event_inventory_item_id);
CREATE INDEX idx_item_assignments_item ON item_assignments(item_id);
CREATE INDEX idx_item_assignments_user ON item_assignments(assigned_to_user_id);
CREATE INDEX idx_draugove_requisitions_tuntas ON draugove_requisitions(tuntas_id);
CREATE INDEX idx_draugove_requisitions_unit ON draugove_requisitions(organizational_unit_id);
CREATE INDEX idx_pastovykle_inventory_pastovykle ON pastovykle_inventory(pastovykle_id);
CREATE INDEX idx_sync_operations_client_timestamp ON sync_operations(client_timestamp);
CREATE INDEX idx_invitations_code ON invitations(code);
CREATE INDEX idx_invitations_tuntas ON invitations(tuntas_id);
CREATE INDEX idx_bendras_inventory_requests_tuntas ON bendras_inventory_requests(tuntas_id);
CREATE INDEX idx_bendras_inventory_requests_user ON bendras_inventory_requests(requested_by_user_id);
CREATE INDEX idx_bendras_inventory_requests_item ON bendras_inventory_requests(item_id);
CREATE INDEX idx_bendras_inventory_requests_top_status ON bendras_inventory_requests(top_level_status);
CREATE INDEX idx_bendras_inventory_request_items_request ON bendras_inventory_request_items(request_id);
CREATE INDEX idx_unit_assignments_user ON unit_assignments(user_id);
CREATE INDEX idx_unit_assignments_tuntas ON unit_assignments(tuntas_id);
CREATE INDEX idx_unit_assignments_unit ON unit_assignments(organizational_unit_id);
CREATE INDEX idx_user_draugove_memberships_user ON user_draugove_memberships(user_id);
CREATE INDEX idx_user_draugove_memberships_tuntas ON user_draugove_memberships(tuntas_id);
CREATE INDEX idx_user_draugove_memberships_unit ON user_draugove_memberships(organizational_unit_id);
