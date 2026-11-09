-- MariaDB. Apply to the application's database before starting the finance API.
CREATE TABLE IF NOT EXISTS user_tab (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120), age INT, gender VARCHAR(20), hobby VARCHAR(255)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    description VARCHAR(255),
    status VARCHAR(16) NOT NULL DEFAULT '已启用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_master (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_type VARCHAR(20) NOT NULL,
    code VARCHAR(48) NOT NULL,
    name VARCHAR(120) NOT NULL,
    extra_info VARCHAR(255),
    rate DECIMAL(19,8),
    effective_date DATE,
    amount_limit DECIMAL(19,2),
    status VARCHAR(16) NOT NULL DEFAULT '已启用',
    UNIQUE (data_type, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    department_id BIGINT REFERENCES fin_master(id),
    role_id BIGINT NOT NULL REFERENCES fin_role(id),
    password_hash VARCHAR(255) NOT NULL,
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    session_version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT '已启用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_permission (
    role_id BIGINT NOT NULL REFERENCES fin_role(id),
    resource VARCHAR(80) NOT NULL,
    can_write BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (role_id, resource)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    operator_id BIGINT NOT NULL REFERENCES fin_user(id),
    operation VARCHAR(64) NOT NULL,
    resource VARCHAR(80) NOT NULL,
    object_id BIGINT,
    result VARCHAR(16) NOT NULL DEFAULT '成功',
    detail TEXT,
    INDEX (operated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_period (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period_code CHAR(7) NOT NULL UNIQUE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    closed_date DATE,
    status VARCHAR(16) NOT NULL DEFAULT '进行中',
    next_voucher_no INT NOT NULL DEFAULT 1,
    CHECK (end_date >= start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    category VARCHAR(16) NOT NULL CHECK (category IN ('资产','负债','权益','成本','损益')),
    balance_side VARCHAR(1) NOT NULL CHECK (balance_side IN ('借','贷')),
    parent_id BIGINT REFERENCES fin_account(id),
    account_level INT NOT NULL,
    dimensions VARCHAR(120),
    status VARCHAR(16) NOT NULL DEFAULT '已启用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_voucher (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    period_id BIGINT NOT NULL REFERENCES fin_period(id),
    serial_no INT NOT NULL,
    voucher_no VARCHAR(40) NOT NULL UNIQUE,
    voucher_date DATE NOT NULL,
    summary VARCHAR(255) NOT NULL,
    debit_total DECIMAL(19,2) NOT NULL,
    credit_total DECIMAL(19,2) NOT NULL,
    maker_id BIGINT NOT NULL REFERENCES fin_user(id),
    reviewer_id BIGINT REFERENCES fin_user(id),
    posted_by BIGINT REFERENCES fin_user(id),
    posted_at DATETIME,
    reversal_of BIGINT UNIQUE REFERENCES fin_voucher(id),
    status VARCHAR(16) NOT NULL DEFAULT '草稿',
    UNIQUE (period_id, serial_no),
    INDEX (period_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_voucher_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_id BIGINT NOT NULL REFERENCES fin_voucher(id),
    line_no INT NOT NULL,
    account_id BIGINT NOT NULL REFERENCES fin_account(id),
    summary VARCHAR(255),
    debit DECIMAL(19,2) NOT NULL DEFAULT 0,
    credit DECIMAL(19,2) NOT NULL DEFAULT 0,
    department_id BIGINT REFERENCES fin_master(id),
    project_id BIGINT REFERENCES fin_master(id),
    party_id BIGINT REFERENCES fin_master(id),
    CHECK ((debit <> 0 AND credit = 0) OR (credit <> 0 AND debit = 0)),
    UNIQUE (voucher_id, line_no),
    INDEX (account_id, voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_bank_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    bank_name VARCHAR(120),
    account_number VARCHAR(64) UNIQUE,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    account_type VARCHAR(16) NOT NULL CHECK (account_type IN ('银行账户','现金账户')),
    ledger_account_id BIGINT NOT NULL REFERENCES fin_account(id),
    opening_balance DECIMAL(19,2) NOT NULL DEFAULT 0,
    balance DECIMAL(19,2) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT '已启用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_cash_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    business_type VARCHAR(16) NOT NULL DEFAULT 'GENERAL',
    direction VARCHAR(4) NOT NULL CHECK (direction IN ('收款','付款')),
    party_id BIGINT REFERENCES fin_master(id),
    bank_account_id BIGINT NOT NULL REFERENCES fin_bank_account(id),
    counterpart_account_id BIGINT NOT NULL REFERENCES fin_account(id),
    transaction_date DATE NOT NULL,
    amount DECIMAL(19,2) NOT NULL CHECK (amount > 0),
    exchange_rate DECIMAL(19,8) NOT NULL DEFAULT 1 CHECK (exchange_rate > 0),
    purpose VARCHAR(255) NOT NULL,
    maker_id BIGINT NOT NULL REFERENCES fin_user(id),
    voucher_id BIGINT UNIQUE REFERENCES fin_voucher(id),
    status VARCHAR(16) NOT NULL DEFAULT '待审核',
    INDEX (bank_account_id, transaction_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_trade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    side CHAR(2) NOT NULL CHECK (side IN ('AR','AP')),
    order_no VARCHAR(64) NOT NULL UNIQUE,
    party_id BIGINT NOT NULL REFERENCES fin_master(id),
    order_date DATE NOT NULL,
    due_date DATE NOT NULL,
    amount DECIMAL(19,2) NOT NULL CHECK (amount > 0),
    tax_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    exchange_rate DECIMAL(19,8) NOT NULL DEFAULT 1 CHECK (exchange_rate > 0),
    settled_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    trade_account_id BIGINT NOT NULL REFERENCES fin_account(id),
    counterpart_account_id BIGINT NOT NULL REFERENCES fin_account(id),
    tax_account_id BIGINT REFERENCES fin_account(id),
    department_id BIGINT REFERENCES fin_master(id),
    project_id BIGINT REFERENCES fin_master(id),
    source_type VARCHAR(40),
    source_no VARCHAR(80),
    maker_id BIGINT NOT NULL REFERENCES fin_user(id),
    voucher_id BIGINT UNIQUE REFERENCES fin_voucher(id),
    status VARCHAR(16) NOT NULL DEFAULT '待审核',
    CHECK (due_date >= order_date AND tax_amount BETWEEN 0 AND amount),
    CHECK (settled_amount BETWEEN 0 AND amount),
    UNIQUE (side, source_type, source_no),
    INDEX (side, party_id, due_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_settlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_id BIGINT NOT NULL REFERENCES fin_trade(id),
    cash_order_id BIGINT NOT NULL REFERENCES fin_cash_order(id),
    amount DECIMAL(19,2) NOT NULL CHECK (amount > 0),
    settled_on DATE NOT NULL,
    reversed_on DATE,
    voucher_id BIGINT UNIQUE REFERENCES fin_voucher(id),
    reversal_voucher_id BIGINT UNIQUE REFERENCES fin_voucher(id),
    request_key VARCHAR(64) NOT NULL UNIQUE,
    INDEX (trade_id, settled_on),
    INDEX (cash_order_id, reversed_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_note (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    note_no VARCHAR(64) NOT NULL UNIQUE,
    party_id BIGINT NOT NULL REFERENCES fin_master(id),
    note_type VARCHAR(16) NOT NULL CHECK (note_type IN ('商业承兑','银行承兑')),
    amount DECIMAL(19,2) NOT NULL CHECK (amount > 0),
    issued_on DATE NOT NULL,
    due_date DATE NOT NULL,
    cash_order_id BIGINT UNIQUE REFERENCES fin_cash_order(id),
    status VARCHAR(16) NOT NULL DEFAULT '已签发',
    CHECK (due_date >= issued_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_bank_statement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bank_account_id BIGINT NOT NULL REFERENCES fin_bank_account(id),
    transaction_date DATE NOT NULL,
    reference_no VARCHAR(96) NOT NULL,
    summary VARCHAR(255) NOT NULL,
    direction VARCHAR(4) NOT NULL CHECK (direction IN ('收款','付款')),
    amount DECIMAL(19,2) NOT NULL CHECK (amount > 0),
    matched_order_id BIGINT UNIQUE REFERENCES fin_cash_order(id),
    match_method VARCHAR(16),
    UNIQUE (bank_account_id, transaction_date, reference_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_reconciliation_lock (
    bank_account_id BIGINT NOT NULL REFERENCES fin_bank_account(id),
    period_id BIGINT NOT NULL REFERENCES fin_period(id),
    locked_by BIGINT NOT NULL REFERENCES fin_user(id),
    locked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (bank_account_id, period_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_cash_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    bank_account_id BIGINT NOT NULL REFERENCES fin_bank_account(id),
    cycle VARCHAR(8) NOT NULL CHECK (cycle IN ('周度','月度')),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    planned_receipts DECIMAL(19,2) NOT NULL CHECK (planned_receipts >= 0),
    planned_payments DECIMAL(19,2) NOT NULL CHECK (planned_payments >= 0),
    UNIQUE (bank_account_id, cycle, start_date, end_date),
    CHECK (start_date <= end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_workflow (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    version INT NOT NULL CHECK (version > 0),
    department_id BIGINT REFERENCES fin_master(id),
    expense_type_id BIGINT REFERENCES fin_master(id),
    min_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    max_amount DECIMAL(19,2),
    status VARCHAR(16) NOT NULL DEFAULT '草稿',
    CHECK (max_amount IS NULL OR max_amount >= min_amount),
    UNIQUE (name, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_workflow_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    workflow_id BIGINT NOT NULL REFERENCES fin_workflow(id),
    step_no INT NOT NULL,
    role_id BIGINT NOT NULL REFERENCES fin_role(id),
    name VARCHAR(120) NOT NULL,
    mode VARCHAR(8) NOT NULL CHECK (mode IN ('串行','或签','会签')),
    UNIQUE (workflow_id, step_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_loan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    loan_no VARCHAR(64) NOT NULL UNIQUE,
    loan_account_id BIGINT NOT NULL REFERENCES fin_account(id),
    cash_order_id BIGINT UNIQUE REFERENCES fin_cash_order(id),
    applicant_id BIGINT NOT NULL REFERENCES fin_user(id),
    purpose VARCHAR(255) NOT NULL,
    amount DECIMAL(19,2) NOT NULL CHECK (amount > 0),
    remaining_amount DECIMAL(19,2) NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT '待审批',
    CHECK (remaining_amount BETWEEN 0 AND amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_loan_repayment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    loan_id BIGINT NOT NULL REFERENCES fin_loan(id),
    cash_order_id BIGINT NOT NULL UNIQUE REFERENCES fin_cash_order(id),
    amount DECIMAL(19,2) NOT NULL CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_claim (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_no VARCHAR(64) NOT NULL UNIQUE,
    voucher_id BIGINT UNIQUE REFERENCES fin_voucher(id),
    applicant_id BIGINT NOT NULL REFERENCES fin_user(id),
    department_id BIGINT NOT NULL REFERENCES fin_master(id),
    expense_type_id BIGINT NOT NULL REFERENCES fin_master(id),
    loan_id BIGINT REFERENCES fin_loan(id),
    loan_offset DECIMAL(19,2) NOT NULL DEFAULT 0,
    amount DECIMAL(19,2) NOT NULL,
    workflow_id BIGINT REFERENCES fin_workflow(id),
    approval_status VARCHAR(16) NOT NULL DEFAULT '草稿',
    payment_status VARCHAR(16) NOT NULL DEFAULT '待支付',
    cash_order_id BIGINT UNIQUE REFERENCES fin_cash_order(id),
    CHECK (loan_offset BETWEEN 0 AND amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_claim_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT NOT NULL REFERENCES fin_claim(id),
    expense_type_id BIGINT NOT NULL REFERENCES fin_master(id),
    account_id BIGINT NOT NULL REFERENCES fin_account(id),
    department_id BIGINT NOT NULL REFERENCES fin_master(id),
    project_id BIGINT REFERENCES fin_master(id),
    expense_date DATE NOT NULL,
    amount DECIMAL(19,2) NOT NULL CHECK (amount > 0),
    purpose VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_approval_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT NOT NULL REFERENCES fin_claim(id),
    step_id BIGINT NOT NULL REFERENCES fin_workflow_step(id),
    assignee_id BIGINT NOT NULL REFERENCES fin_user(id),
    required_sign BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT '待审批',
    opinion VARCHAR(255),
    handled_at DATETIME,
    UNIQUE (claim_id, step_id, assignee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_attachment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_id BIGINT REFERENCES fin_voucher(id),
    claim_id BIGINT REFERENCES fin_claim(id),
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    file_data MEDIUMBLOB NOT NULL,
    CHECK ((voucher_id IS NULL) <> (claim_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(48) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    category VARCHAR(48) NOT NULL,
    department_id BIGINT NOT NULL REFERENCES fin_master(id),
    custodian_id BIGINT REFERENCES fin_user(id),
    acquisition_date DATE NOT NULL,
    original_value DECIMAL(19,2) NOT NULL CHECK (original_value > 0),
    accumulated_depreciation DECIMAL(19,2) NOT NULL DEFAULT 0,
    method VARCHAR(16) NOT NULL CHECK (method IN ('平均年限法','工作量法','双倍余额递减法')),
    life_months INT NOT NULL CHECK (life_months > 0),
    residual_rate DECIMAL(9,6) NOT NULL CHECK (residual_rate BETWEEN 0 AND 1),
    total_units DECIMAL(19,4),
    asset_account_id BIGINT NOT NULL REFERENCES fin_account(id),
    expense_account_id BIGINT NOT NULL REFERENCES fin_account(id),
    depreciation_account_id BIGINT NOT NULL REFERENCES fin_account(id),
    status VARCHAR(16) NOT NULL DEFAULT '使用中'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_depreciation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL REFERENCES fin_asset(id),
    period_id BIGINT NOT NULL REFERENCES fin_period(id),
    amount DECIMAL(19,2) NOT NULL CHECK (amount >= 0),
    adjustment DECIMAL(19,2) NOT NULL DEFAULT 0,
    accumulated_amount DECIMAL(19,2) NOT NULL,
    voucher_id BIGINT UNIQUE REFERENCES fin_voucher(id),
    UNIQUE (asset_id, period_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_depreciation_adjustment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    depreciation_id BIGINT NOT NULL REFERENCES fin_depreciation(id),
    amount DECIMAL(19,2) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    voucher_id BIGINT NOT NULL UNIQUE REFERENCES fin_voucher(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_asset_movement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    movement_no VARCHAR(64) NOT NULL UNIQUE,
    asset_id BIGINT NOT NULL REFERENCES fin_asset(id),
    movement_type VARCHAR(16) NOT NULL CHECK (movement_type IN ('调拨','使用人变更','盘点')),
    old_department_id BIGINT NOT NULL REFERENCES fin_master(id),
    new_department_id BIGINT REFERENCES fin_master(id),
    old_custodian_id BIGINT REFERENCES fin_user(id),
    new_custodian_id BIGINT REFERENCES fin_user(id),
    inventory_result VARCHAR(255),
    movement_date DATE NOT NULL,
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT '待审批'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_asset_disposal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    disposal_no VARCHAR(64) NOT NULL UNIQUE,
    asset_id BIGINT NOT NULL REFERENCES fin_asset(id),
    disposal_type VARCHAR(8) NOT NULL CHECK (disposal_type IN ('报废','出售')),
    disposal_date DATE NOT NULL,
    net_value DECIMAL(19,2) NOT NULL,
    proceeds DECIMAL(19,2) NOT NULL CHECK (proceeds >= 0),
    reason VARCHAR(255) NOT NULL,
    voucher_id BIGINT UNIQUE REFERENCES fin_voucher(id),
    status VARCHAR(16) NOT NULL DEFAULT '待审批'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_bad_debt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    trade_id BIGINT NOT NULL REFERENCES fin_trade(id),
    original_id BIGINT REFERENCES fin_bad_debt(id),
    period_id BIGINT NOT NULL REFERENCES fin_period(id),
    transaction_date DATE NOT NULL,
    treatment VARCHAR(16) NOT NULL CHECK (treatment IN ('计提','核销','收回')),
    amount DECIMAL(19,2) NOT NULL CHECK (amount > 0),
    voucher_id BIGINT UNIQUE REFERENCES fin_voucher(id),
    status VARCHAR(16) NOT NULL DEFAULT '已确认'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    report_type VARCHAR(32) NOT NULL,
    owner_id BIGINT NOT NULL REFERENCES fin_user(id),
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT '已启用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_report_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    report_id BIGINT NOT NULL REFERENCES fin_report(id),
    line_no INT NOT NULL,
    label VARCHAR(120) NOT NULL,
    account_id BIGINT NOT NULL REFERENCES fin_account(id),
    metric VARCHAR(16) NOT NULL CHECK (metric IN ('期末余额','借方发生','贷方发生','净发生')),
    factor DECIMAL(12,6) NOT NULL DEFAULT 1,
    INDEX (report_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_backup_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    backup_at DATETIME NOT NULL,
    backup_type VARCHAR(40) NOT NULL,
    retain_until DATE NOT NULL,
    result VARCHAR(255),
    status VARCHAR(16) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE IF NOT EXISTS fin_setting (
    setting_key VARCHAR(48) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO fin_setting (setting_key, setting_value)
VALUES ('account_segments', ''), ('aging_bounds', '30,60,90'), ('period_timeline', '')
ON DUPLICATE KEY UPDATE setting_key=VALUES(setting_key);

CREATE TABLE IF NOT EXISTS fin_year_carryover (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_period_id BIGINT NOT NULL UNIQUE REFERENCES fin_period(id),
    target_period_id BIGINT NOT NULL UNIQUE REFERENCES fin_period(id),
    operator_id BIGINT NOT NULL REFERENCES fin_user(id),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fin_year_opening (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    carryover_id BIGINT NOT NULL REFERENCES fin_year_carryover(id),
    account_id BIGINT NOT NULL REFERENCES fin_account(id),
    department_id BIGINT REFERENCES fin_master(id),
    project_id BIGINT REFERENCES fin_master(id),
    party_id BIGINT REFERENCES fin_master(id),
    balance DECIMAL(19,2) NOT NULL,
    INDEX (carryover_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
