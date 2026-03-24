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



-- Tuntai
CREATE TABLE tuntai (
                        id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                        name VARCHAR(100) NOT NULL,
                        krastas VARCHAR(100),
                        contact_email VARCHAR(255),
                        status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED')),
                        approved_by_super_admin_id UUID REFERENCES super_admins(id),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        approved_at TIMESTAMP
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

-- Organizational units
CREATE TABLE organizational_units (
                                      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                      tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                      parent_id UUID REFERENCES organizational_units(id),
                                      name VARCHAR(100) NOT NULL,
                                      type VARCHAR(30) NOT NULL CHECK (type IN ('DRAUGOVE', 'SKILTIS', 'GAUJA', 'GILDIJA', 'BURELI', 'VYRESNIUJU_DRAUGOVE')),
                                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Roles
CREATE TABLE roles (
                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                       tuntas_id UUID REFERENCES tuntai(id) ON DELETE CASCADE,
                       name VARCHAR(100) NOT NULL,
                       is_system_role BOOLEAN DEFAULT FALSE,
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
                                  scope VARCHAR(20) DEFAULT 'ALL' CHECK (scope IN ('ALL', 'OWN_DRAUGOVE')),
                                  UNIQUE(role_id, permission_id)
);

-- User roles
CREATE TABLE user_roles (
                            id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
                            tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                            organizational_unit_id UUID REFERENCES organizational_units(id),
                            assigned_by_user_id UUID REFERENCES users(id),
                            assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            expires_at TIMESTAMP
);

-- Locations
CREATE TABLE locations (
                           id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                           tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                           name VARCHAR(100) NOT NULL,
                           address TEXT,
                           description TEXT,
                           created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Items
-- Note: original_owner_id is UUID without FK constraint because it can reference
-- either tuntai or organizational_units depending on transfer history
CREATE TABLE items (
                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                       tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                       owner_type VARCHAR(20) NOT NULL CHECK (owner_type IN ('TUNTAS', 'DRAUGOVE')),
                       owner_id UUID NOT NULL,
                       original_owner_id UUID,
                       name VARCHAR(200) NOT NULL,
                       description TEXT,
                       category VARCHAR(20) NOT NULL CHECK (category IN ('COLLECTIVE', 'ASSIGNED', 'INDIVIDUAL')),
                       condition VARCHAR(20) DEFAULT 'GOOD' CHECK (condition IN ('GOOD', 'DAMAGED', 'WRITTEN_OFF')),
                       quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
                       location_id UUID REFERENCES locations(id),
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
                                from_owner_type VARCHAR(20) NOT NULL,
                                from_owner_id UUID NOT NULL,
                                to_owner_type VARCHAR(20) NOT NULL,
                                to_owner_id UUID NOT NULL,
                                transfer_reason VARCHAR(30) NOT NULL CHECK (transfer_reason IN ('REQUISITION', 'DISSOLUTION', 'MEMBER_DEPARTURE', 'MANUAL')),
                                initiated_by_user_id UUID REFERENCES users(id),
                                approved_by_user_id UUID REFERENCES users(id),
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
                        location_id UUID REFERENCES locations(id),
                        organizational_unit_id UUID REFERENCES organizational_units(id),
                        created_by_user_id UUID REFERENCES users(id),
                        status VARCHAR(20) DEFAULT 'PLANNING' CHECK (status IN ('PLANNING', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
                        notes TEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        CHECK (end_date >= start_date)
);

-- Stovykla details
CREATE TABLE stovykla_details (
                                  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                  event_id UUID NOT NULL UNIQUE REFERENCES events(id) ON DELETE CASCADE,
                                  registration_deadline DATE,
                                  expected_participants INTEGER,
                                  actual_participants INTEGER
);

-- Pastovyklės
CREATE TABLE pastovykles (
                             id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                             event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                             name VARCHAR(100) NOT NULL,
                             responsible_user_id UUID REFERENCES users(id),
                             age_group VARCHAR(30) CHECK (age_group IN ('VILKAI', 'SKAUTAI', 'PATYRE_SKAUTAI', 'MIXED')),
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
                             role VARCHAR(30) NOT NULL CHECK (role IN ('VIRSININKAS', 'KOMENDANTAS', 'UKVEDYS', 'PASTOVYKLES_GURU', 'VADOVAS', 'SAVANORIS', 'PATYRE_SKAUTAS', 'SKAUTAS')),
                             assigned_by_user_id UUID REFERENCES users(id),
                             assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                             UNIQUE(event_id, user_id, role)
);

-- Draugovė requisitions
CREATE TABLE draugove_requisitions (
                                       id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                                       tuntas_id UUID NOT NULL REFERENCES tuntai(id) ON DELETE CASCADE,
                                       organizational_unit_id UUID NOT NULL REFERENCES organizational_units(id),
                                       event_id UUID REFERENCES events(id),
                                       created_by_user_id UUID NOT NULL REFERENCES users(id),
                                       reviewed_by_user_id UUID REFERENCES users(id),
                                       status VARCHAR(30) DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED', 'PARTIALLY_APPROVED', 'APPROVED', 'REJECTED')),
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
                                            item_id UUID NOT NULL REFERENCES items(id),
                                            quantity_requested INTEGER NOT NULL CHECK (quantity_requested > 0),
                                            quantity_approved INTEGER CHECK (quantity_approved >= 0),
                                            rejection_reason TEXT,
                                            notes TEXT,
                                            CHECK (quantity_approved <= quantity_requested)
);

-- Reservations
CREATE TABLE reservations (
                              id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
                              item_id UUID NOT NULL REFERENCES items(id) ON DELETE CASCADE,
                              tuntas_id UUID NOT NULL REFERENCES tuntai(id),
                              reserved_by_user_id UUID NOT NULL REFERENCES users(id),
                              approved_by_user_id UUID REFERENCES users(id),
                              event_id UUID REFERENCES events(id),
                              quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
                              start_date DATE NOT NULL,
                              end_date DATE NOT NULL,
                              status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'ACTIVE', 'RETURNED', 'CANCELLED', 'REJECTED')),
                              notes TEXT,
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                              CHECK (end_date >= start_date)
);

CREATE TRIGGER update_reservations_updated_at
    BEFORE UPDATE ON reservations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

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
--Invitations
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


-- Indexes
CREATE INDEX idx_items_tuntas ON items(tuntas_id);
CREATE INDEX idx_items_owner ON items(owner_type, owner_id);
CREATE INDEX idx_items_status ON items(status);
CREATE INDEX idx_reservations_item ON reservations(item_id);
CREATE INDEX idx_reservations_dates ON reservations(start_date, end_date);
CREATE INDEX idx_reservations_status ON reservations(status);
CREATE INDEX idx_sync_operations_status ON sync_operations(status);
CREATE INDEX idx_sync_operations_device ON sync_operations(device_id);
CREATE INDEX idx_user_roles_user ON user_roles(user_id);
CREATE INDEX idx_user_roles_tuntas ON user_roles(tuntas_id);
CREATE INDEX idx_events_tuntas ON events(tuntas_id);
CREATE INDEX idx_events_dates ON events(start_date, end_date);
CREATE INDEX idx_item_assignments_item ON item_assignments(item_id);
CREATE INDEX idx_item_assignments_user ON item_assignments(assigned_to_user_id);
CREATE INDEX idx_draugove_requisitions_tuntas ON draugove_requisitions(tuntas_id);
CREATE INDEX idx_draugove_requisitions_unit ON draugove_requisitions(organizational_unit_id);
CREATE INDEX idx_pastovykle_inventory_pastovykle ON pastovykle_inventory(pastovykle_id);
CREATE INDEX idx_sync_operations_client_timestamp ON sync_operations(client_timestamp);
CREATE INDEX idx_invitations_code ON invitations(code);
CREATE INDEX idx_invitations_tuntas ON invitations(tuntas_id);