# VPNResellers API v4.1 — Technical Reference Manual

This reference manual documents the essential endpoints, parameters, schemas, and error codes for the **VPNResellers API v4.1**.

---

## 🔑 Base URL & Authentication

- **Base URL**: `https://api.vpnresellers.com/v4_1`
- **Authentication**: `Authorization: Bearer {token}`
- **Headers**:
  ```http
  Authorization: Bearer YOUR_API_TOKEN
  Content-Type: application/json
  Accept: application/json
  ```

---

## 📊 HTTP Response Codes & Errors

| Code | Status | Meaning |
|---|---|---|
| **200** | OK | Request completed successfully |
| **201** | Created | Resource created successfully |
| **400** | Bad Request | Malformed parameters or request body |
| **401** | Unauthorized | Invalid or missing API Bearer Token |
| **402** | Insufficient Balance | Account balance is empty or low |
| **403** | Forbidden | Action not permitted for this token |
| **404** | Not Found | Target account, server, or resource not found |
| **405** | Method Not Allowed | Incorrect HTTP method used |
| **422** | Validation Error | Username already taken or invalid parameters |

---

## 👤 1. Accounts Endpoints

### 1.1 Check Username Availability
- **Method**: `GET /v4_1/accounts/check_username`
- **Query Parameter**: `username` (string, required)
- **Response (200 OK)**:
  ```json
  {
    "message": "The username checking was successfully.",
    "code": 200
  }
  ```

### 1.2 List Accounts
- **Method**: `GET /v4_1/accounts`
- **Query Parameters**:
  - `page` (integer, optional)
  - `per_page` (integer, optional, default 15)
  - `status` (string, optional: `"Active"` or `"Disabled"`)
- **Response (200 OK)**:
  ```json
  {
    "data": [
      {
        "id": 9,
        "username": "user123",
        "status": "Active",
        "wg_ip": "10.250.121.219",
        "wg_private_key": "XE1pI01BnHB1EmidlHdm55r2qHKBSWCCJtXeS9gC+WU=",
        "wg_public_key": "w4oWBcymg+W+24NTb7FolqW9sumDnO0vhXXP19iQomM=",
        "expired_at": "2026-12-31T23:59:59.000000Z",
        "created": "2026-07-30 14:00:00"
      }
    ],
    "meta": {
      "current_page": 1,
      "per_page": 15,
      "total": 1
    },
    "code": 200
  }
  ```

### 1.3 Create Account
- **Method**: `POST /v4_1/accounts`
- **Request Body**:
  ```json
  {
    "username": "client_user_001",
    "password": "SecurePassword123",
    "customer": {
      "first_name": "John",
      "last_name": "Doe",
      "email": "user@example.com",
      "project_id": 1
    }
  }
  ```
- **Response (201 Created)**:
  ```json
  {
    "data": {
      "id": 105,
      "username": "client_user_001",
      "status": "Active",
      "wg_ip": "10.250.121.220",
      "wg_private_key": "...",
      "wg_public_key": "...",
      "expired_at": "2026-08-30T00:00:00.000000Z"
    },
    "code": 201
  }
  ```

### 1.4 Retrieve Account Details
- **Method**: `GET /v4_1/accounts/{account_id}`
- **Response (200 OK)**: Contains full account object (`status`, `wg_ip`, `wg_private_key`, `expired_at`).

### 1.5 Enable Account
- **Method**: `PUT /v4_1/accounts/{account_id}/enable`
- **Response (200 OK)**: Enables a previously disabled account upon subscription renewal.

### 1.6 Disable Account
- **Method**: `PUT /v4_1/accounts/{account_id}/disable`
- **Response (200 OK)**: Suspends account tunnel access upon subscription expiry or cancellation.

### 1.7 Delete Account
- **Method**: `DELETE /v4_1/accounts/{account_id}`
- **Response (200 OK)**: Deletes account permanently.

---

## ⚡ 2. WireGuard & OpenVPN Configurations

### Fetch Server Config
- **Method**: `GET /v4_1/accounts/{account_id}/config`
- **Query Parameters**:
  - `server_id` (integer or string location code)
  - `protocol` (`"wireguard"` or `"openvpn"`)
- **Header**: `Accept: application/json` or `text/html; charset=UTF-8`

---

## 🛠️ Integration with Cloudflare Worker Backend

Our live Cloudflare Worker (`backend/worker.js`) interfaces with VPNResellers v4.1 automatically:
1. **Secret Token**: `VPNRESELLERS_API_TOKEN` stored in Cloudflare's server vault.
2. **Account Provisioning**: Automatically checks username availability and creates WireGuard keys upon 3-Day Trial or Google Play purchase.
3. **Lifecycle Management**: Automatically calls `PUT /disable` on expired accounts and `PUT /enable` on renewed subscriptions.
