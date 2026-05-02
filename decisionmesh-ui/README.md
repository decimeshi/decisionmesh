# 🔐 Identity Provider Setup Guide
### Keycloak & AWS Cognito — One-Time Configuration for New Developers

> This guide walks through the **complete, end-to-end setup** for both Keycloak (self-hosted)
> and AWS Cognito (managed cloud) as identity providers for a multi-tenant SaaS application.
> Follow only the section relevant to your environment.

---

## 📋 Table of Contents

- [Mental Model — How Auth Works](#mental-model)
- [Part 1 — Keycloak Setup](#part-1--keycloak-setup)
    - [Step 1 — Install & Start Keycloak](#step-1--install--start-keycloak)
    - [Step 2 — Create a Realm](#step-2--create-a-realm)
    - [Step 3 — Create a Client](#step-3--create-a-client)
    - [Step 4 — Configure Client Scopes & Token Mappers](#step-4--configure-client-scopes--token-mappers)
    - [Step 5 — Create Realm Roles](#step-5--create-realm-roles)
    - [Step 6 — Configure Admin Service Account](#step-6--configure-admin-service-account)
    - [Step 7 — Configure Google as Identity Provider (Optional)](#step-7--configure-google-as-identity-provider-optional)
    - [Step 8 — Configure application.properties](#step-8--configure-applicationproperties)
    - [Step 9 — Verify Setup with Token Debugger](#step-9--verify-setup-with-token-debugger)
- [Part 2 — AWS Cognito Setup](#part-2--aws-cognito-setup)
    - [Step 1 — Create a User Pool](#step-1--create-a-user-pool)
    - [Step 2 — Configure Sign-In Options](#step-2--configure-sign-in-options)
    - [Step 3 — Configure Security Requirements](#step-3--configure-security-requirements)
    - [Step 4 — Create an App Client](#step-4--create-an-app-client)
    - [Step 5 — Add Custom Attributes](#step-5--add-custom-attributes)
    - [Step 6 — Configure Lambda Triggers](#step-6--configure-lambda-triggers)
    - [Step 7 — Set Up a Domain](#step-7--set-up-a-domain)
    - [Step 8 — Configure Google as Identity Provider (Optional)](#step-8--configure-google-as-identity-provider-optional)
    - [Step 9 — Configure Frontend & Backend](#step-9--configure-frontend--backend)
    - [Step 10 — Verify Setup](#step-10--verify-setup)
- [Comparison — Keycloak vs Cognito](#comparison--keycloak-vs-cognito)
- [Troubleshooting](#troubleshooting)

---

## Mental Model

Before setting up either provider, understand what we're building:

```
USER REGISTERS / LOGS IN
        │
        ▼
  Identity Provider          ← Keycloak OR Cognito
  (handles auth, issues JWT)
        │
        ▼ JWT token contains:
  {
    "sub":         "keycloak-user-uuid",   ← externalId in your DB
    "email":       "user@example.com",
    "tenantId":    "your-db-tenant-uuid",  ← set by your backend after onboarding
    "userId":      "your-db-user-uuid",    ← set by your backend after onboarding
    "accountType": "INDIVIDUAL|ORGANIZATION"
  }
        │
        ▼
  YOUR BACKEND API           ← validates JWT on every request
  (reads tenantId from token, scopes all DB queries to that tenant)
        │
        ▼
  DATABASE                   ← all tables have tenant_id column
```

**Key principle:** The IdP handles **who you are**. Your backend handles **what tenant you belong to**.

---

## Part 1 — Keycloak Setup

> **Prerequisites:** Docker installed, or a Java 17+ runtime.

---

### Step 1 — Install & Start Keycloak

#### Option A — Docker (Recommended for local dev)

```bash
docker run -d \
  --name keycloak \
  -p 8180:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:24.0.1 start-dev
```

#### Option B — Docker Compose (Recommended for teams)

```yaml
# docker-compose.yml
version: '3.8'
services:
  keycloak:
    image: quay.io/keycloak/keycloak:24.0.1
    command: start-dev
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: keycloak
      KC_DB_PASSWORD: keycloak
    ports:
      - "8180:8080"
    depends_on:
      - postgres

  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: keycloak
      POSTGRES_USER: keycloak
      POSTGRES_PASSWORD: keycloak
    volumes:
      - keycloak_data:/var/lib/postgresql/data

volumes:
  keycloak_data:
```

```bash
docker-compose up -d
```

✅ Open http://localhost:8180 → login with `admin / admin`

---

### Step 2 — Create a Realm

A **Realm** is an isolated space — like a separate tenant of Keycloak itself.

```
Keycloak Admin Console → http://localhost:8180
  → Top-left dropdown "master" → Create Realm
  → Realm name: decisionmesh
  → Enabled: ON
  → Create
```

> ⚠️ **Never use the `master` realm for your application.** Master is for Keycloak administration only.

After creating, confirm you're inside the `decisionmesh` realm — the top-left dropdown should show `decisionmesh`.

---

### Step 3 — Create a Client

A **Client** represents your frontend application that talks to Keycloak.

```
Realm: decisionmesh
  → Clients → Create client

  General Settings:
    Client type:  OpenID Connect
    Client ID:    control-plane-web
    Name:         Control Plane Web

  Capability Config:
    Client authentication:  OFF       ← public client (SPA)
    Authorization:          OFF
    Standard flow:          ON        ← authorization code flow
    Direct access grants:   OFF

  Login Settings:
    Root URL:               http://localhost:5173
    Home URL:               http://localhost:5173
    Valid redirect URIs:    http://localhost:5173/*
    Valid post logout URIs: http://localhost:5173/*
    Web origins:            http://localhost:5173

  → Save
```

> 📌 For **production**, replace `http://localhost:5173` with your real domain.

---

### Step 4 — Configure Client Scopes & Token Mappers

This is what puts **custom claims** (`tenantId`, `userId`, `accountType`) into the JWT automatically.

#### 4a — Open the dedicated scope

```
Clients → control-plane-web
  → Client Scopes tab
  → Click: control-plane-web-dedicated
  → Mappers tab → Add mapper → By configuration
```

#### 4b — Add `tenantId` mapper

```
Mapper type:        User Attribute
Name:               tenant-id-mapper
User Attribute:     tenantId
Token Claim Name:   tenantId
Claim JSON Type:    String
Add to ID token:    ON
Add to access token: ON
Add to userinfo:    ON
→ Save
```

#### 4c — Add `userId` mapper

```
Add mapper → By configuration
Mapper type:        User Attribute
Name:               user-id-mapper
User Attribute:     userId
Token Claim Name:   userId
Claim JSON Type:    String
Add to ID token:    ON
Add to access token: ON
Add to userinfo:    ON
→ Save
```

#### 4d — Add `accountType` mapper

```
Add mapper → By configuration
Mapper type:        User Attribute
Name:               account-type-mapper
User Attribute:     accountType
Token Claim Name:   accountType
Claim JSON Type:    String
Add to ID token:    ON
Add to access token: ON
Add to userinfo:    ON
→ Save
```

✅ **After this, every JWT from this client will automatically contain these claims** — no code needed in the IdP.

---

### Step 5 — Create Realm Roles

```
Realm: decisionmesh → Realm roles → Create role

Create these roles one by one:
  - TENANT_OWNER    ← assigned to org/account creator
  - TENANT_ADMIN    ← invited admin users
  - TENANT_MEMBER   ← regular invited users
```

---

### Step 6 — Configure Admin Service Account

Your **backend** calls Keycloak Admin API to write `tenantId` and create groups.
It needs its own client with credentials.

#### 6a — Create a confidential client for the backend

```
Clients → Create client
  Client type:            OpenID Connect
  Client ID:              decisionmesh-backend
  Client authentication:  ON    ← confidential client
  Service accounts:       ON    ← enables client_credentials grant
  → Save
```

#### 6b — Get the client secret

```
Clients → decisionmesh-backend
  → Credentials tab
  → Copy the Client Secret → paste into your .env / application.properties
```

#### 6c — Grant admin permissions to the backend client

```
Clients → decisionmesh-backend
  → Service Account Roles tab
  → Assign role → Filter by clients → realm-management
  → Assign these roles:
      ✅ manage-users
      ✅ view-users
      ✅ manage-realm
```

> ⚠️ `manage-users` is required for writing user attributes and creating groups.

---

### Step 7 — Configure Google as Identity Provider (Optional)

If users sign in with Google (`@gmail.com`):

#### 7a — Create Google OAuth credentials

```
Google Cloud Console → APIs & Services → Credentials
  → Create OAuth 2.0 Client ID
  → Application type: Web application
  → Authorized redirect URIs:
      http://localhost:8180/realms/decisionmesh/broker/google/endpoint
  → Save → copy Client ID and Client Secret
```

#### 7b — Add Google IdP in Keycloak

```
Realm: decisionmesh
  → Identity Providers → Add provider → Google
  → Client ID:       (paste from Google)
  → Client Secret:   (paste from Google)
  → First login flow: first broker login
  → Sync mode:       FORCE
  → Save
```

#### 7c — Map Google email to Keycloak user

```
Identity Providers → Google → Mappers → Add mapper
  Name:           email-mapper
  Mapper type:    Attribute Importer
  Claim:          email
  User attribute: email
  → Save
```

---

### Step 8 — Configure application.properties

Add these to your Quarkus backend:

```properties
# ── Keycloak OIDC (token validation) ─────────────────────────
quarkus.oidc.auth-server-url=http://localhost:8180/realms/decisionmesh
quarkus.oidc.client-id=control-plane-web
quarkus.oidc.application-type=service

# ── Keycloak Admin API (attribute writeback + group management) ─
keycloak.admin.url=http://localhost:8180
keycloak.admin.realm=decisionmesh
keycloak.admin.client-id=decisionmesh-backend
keycloak.admin.client-secret=PASTE_SECRET_HERE

# ── CORS ──────────────────────────────────────────────────────
quarkus.http.cors=true
quarkus.http.cors.origins=http://localhost:5173
quarkus.http.cors.methods=GET,POST,PUT,DELETE,PATCH,OPTIONS
quarkus.http.cors.headers=Authorization,Content-Type
```

And your frontend `.env.development`:

```env
VITE_KEYCLOAK_URL=http://localhost:8180
VITE_KEYCLOAK_REALM=decisionmesh
VITE_KEYCLOAK_CLIENT_ID=control-plane-web
VITE_API_BASE_URL=http://localhost:8080/api
```

---

### Step 9 — Verify Setup with Token Debugger

1. Open your app → login
2. Navigate to `/debug/token` in your app (or use jwt.io)
3. Confirm the decoded JWT contains:

```json
{
  "sub":         "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "email":       "user@example.com",
  "tenantId":    "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",
  "userId":      "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz",
  "accountType": "INDIVIDUAL"
}
```

> ⚠️ `tenantId` and `userId` will be **absent** until the user completes the `/onboarding` flow. That's expected — the onboarding page handles this.

---

## Part 2 — AWS Cognito Setup

> **Prerequisites:** AWS account, AWS CLI configured (`aws configure`).

---

### Step 1 — Create a User Pool

```
AWS Console → Cognito → User Pools → Create user pool

  Step 1 — Provider types:
    ✅ Cognito user pool
    (optionally add: Google, Facebook etc. later)

  → Next
```

---

### Step 2 — Configure Sign-In Options

```
  Step 2 — Sign-in experience:
    Sign-in options:
      ✅ Email              ← recommended
      ☐ Username
      ☐ Phone number

    User name requirements:
      ✅ Make username case insensitive

  → Next
```

---

### Step 3 — Configure Security Requirements

```
  Step 3 — Security requirements:
    Password policy:
      ✅ Cognito defaults  (or set custom min length, complexity)

    MFA:
      ○ No MFA             ← for dev/simple setups
      ○ Optional MFA       ← recommended for production

    User account recovery:
      ✅ Enable self-service account recovery
      Recovery message delivery: Email only

  → Next
```

---

### Step 4 — Create an App Client

```
  Step 5 — Integrate your app:
    App type:           Single-page application (SPA)
    App client name:    control-plane-web

    Allowed callback URLs:
      http://localhost:5173/callback      ← dev
      https://yourdomain.com/callback     ← prod

    Allowed sign-out URLs:
      http://localhost:5173               ← dev
      https://yourdomain.com             ← prod

    Identity providers:
      ✅ Cognito user pool
      ✅ Google (if configured)

    OAuth 2.0 grant types:
      ✅ Authorization code with PKCE    ← always use PKCE for SPAs

    OpenID Connect scopes:
      ✅ OpenID
      ✅ Email
      ✅ Profile

  → Next → Create user pool
```

---

### Step 5 — Add Custom Attributes

Custom attributes in Cognito are how you store `tenantId`, `userId`, `accountType`.

```
User Pool → User attributes → Add custom attributes

Add these one at a time:
  Name: tenantId
  Type: String
  Mutable: ✅ Yes

  Name: userId
  Type: String
  Mutable: ✅ Yes

  Name: accountType
  Type: String
  Mutable: ✅ Yes
```

> ⚠️ In Cognito, custom attributes are prefixed with `custom:` in the token:
> `custom:tenantId`, `custom:userId`, `custom:accountType`

#### Allow backend to write these attributes

```
App client → control-plane-web
  → Edit read/write attributes
  → Write access: ✅ custom:tenantId, ✅ custom:userId, ✅ custom:accountType
  → Save
```

---

### Step 6 — Configure Lambda Triggers

Cognito uses **Lambda triggers** where Keycloak uses mappers.
You need a **Pre Token Generation** trigger to inject custom attributes into the JWT.

#### 6a — Create the Lambda function

```bash
# lambda/pre-token-gen/index.js
exports.handler = async (event) => {
  const attrs = event.request.userAttributes;

  event.response = {
    claimsOverrideDetails: {
      claimsToAddOrOverride: {
        tenantId:    attrs['custom:tenantId']    || null,
        userId:      attrs['custom:userId']      || null,
        accountType: attrs['custom:accountType'] || null,
      }
    }
  };

  return event;
};
```

```bash
# Zip and deploy
zip -j lambda.zip index.js

aws lambda create-function \
  --function-name cognito-pre-token-gen \
  --runtime nodejs20.x \
  --role arn:aws:iam::YOUR_ACCOUNT:role/lambda-basic-execution \
  --handler index.handler \
  --zip-file fileb://lambda.zip
```

#### 6b — Attach trigger to User Pool

```
User Pool → User Pool Properties → Lambda Triggers
  → Pre token generation trigger: cognito-pre-token-gen (V2 trigger)
  → Save
```

> ✅ Now every JWT will contain `tenantId`, `userId`, and `accountType` at the top level — exactly like Keycloak.

---

### Step 7 — Set Up a Domain

Cognito needs a domain to host the hosted UI (login page).

#### Option A — Cognito managed domain (easiest)

```
User Pool → App Integration → Domain
  → Cognito domain: decisionmesh-auth
  → Full URL: https://decisionmesh-auth.auth.us-east-1.amazoncognito.com
  → Save
```

#### Option B — Custom domain (production)

```
User Pool → App Integration → Domain
  → Custom domain: auth.yourdomain.com
  → Requires ACM certificate in us-east-1
  → Save
```

---

### Step 8 — Configure Google as Identity Provider (Optional)

#### 8a — Create Google OAuth credentials (same as Keycloak step 7a)

Redirect URI for Cognito:
```
https://decisionmesh-auth.auth.us-east-1.amazoncognito.com/oauth2/idpresponse
```

#### 8b — Add to Cognito

```
User Pool → Sign-in experience → Federated identity provider sign-in
  → Add an identity provider → Google
  → Client ID:     (from Google)
  → Client secret: (from Google)
  → Authorized scope: profile email openid
  → Map attributes:
      Google attribute: email  →  User pool attribute: email
      Google attribute: name   →  User pool attribute: name
  → Save
```

#### 8c — Enable for app client

```
App client → control-plane-web → Edit
  → Identity providers: ✅ Google
  → Save
```

---

### Step 9 — Configure Frontend & Backend

#### Frontend — AWS Amplify (easiest)

```bash
npm install aws-amplify @aws-amplify/ui-react
```

```javascript
// src/auth/cognito.js
import { Amplify } from 'aws-amplify';

Amplify.configure({
  Auth: {
    Cognito: {
      userPoolId:       import.meta.env.VITE_COGNITO_USER_POOL_ID,
      userPoolClientId: import.meta.env.VITE_COGNITO_CLIENT_ID,
      loginWith: {
        oauth: {
          domain:            import.meta.env.VITE_COGNITO_DOMAIN,
          scopes:            ['openid', 'email', 'profile'],
          redirectSignIn:    [import.meta.env.VITE_COGNITO_REDIRECT_URI],
          redirectSignOut:   [import.meta.env.VITE_COGNITO_LOGOUT_URI],
          responseType:      'code',
        }
      }
    }
  }
});
```

```env
# .env.development
VITE_COGNITO_USER_POOL_ID=us-east-1_XXXXXXXXX
VITE_COGNITO_CLIENT_ID=xxxxxxxxxxxxxxxxxxxxxxxxxx
VITE_COGNITO_DOMAIN=decisionmesh-auth.auth.us-east-1.amazoncognito.com
VITE_COGNITO_REDIRECT_URI=http://localhost:5173/callback
VITE_COGNITO_LOGOUT_URI=http://localhost:5173
VITE_API_BASE_URL=http://localhost:8080/api
```

#### Backend — Quarkus OIDC

```properties
# application.properties
quarkus.oidc.auth-server-url=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_XXXXXXXXX
quarkus.oidc.client-id=xxxxxxxxxxxxxxxxxxxxxxxxxx
quarkus.oidc.application-type=service

# Cognito-specific: token must be validated against JWKS
quarkus.oidc.token.issuer=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_XXXXXXXXX
```

#### Backend — Write custom attributes via AWS SDK

```java
// Replace Keycloak Admin API calls with Cognito SDK
@Inject
software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient cognito;

public void writeUserAttributes(String username, UUID tenantId, UUID userId) {
    cognito.adminUpdateUserAttributes(r -> r
        .userPoolId(userPoolId)
        .username(username)
        .userAttributes(
            AttributeType.builder().name("custom:tenantId").value(tenantId.toString()).build(),
            AttributeType.builder().name("custom:userId").value(userId.toString()).build(),
            AttributeType.builder().name("custom:accountType").value("INDIVIDUAL").build()
        )
    );
}
```

---

### Step 10 — Verify Setup

```bash
# Get a token via Cognito hosted UI login, then decode it at jwt.io
# Confirm the token contains:

{
  "sub":         "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "email":       "user@example.com",
  "tenantId":    "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",   ← from Pre Token Gen Lambda
  "userId":      "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz", ← from Pre Token Gen Lambda
  "accountType": "INDIVIDUAL",                             ← from Pre Token Gen Lambda
  "iss":         "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_XXXXXXXXX",
  "token_use":   "access"
}
```

---

## Comparison — Keycloak vs Cognito

| Feature | Keycloak | AWS Cognito |
|---|---|---|
| **Hosting** | Self-hosted (Docker/VM) | Fully managed AWS service |
| **Cost** | Free (infra cost only) | Free tier: 50,000 MAU free |
| **Custom claims** | User Attribute Mappers (UI) | Lambda Pre Token Gen trigger |
| **Admin API** | REST API (`/admin/realms/...`) | AWS SDK / CLI |
| **Google login** | Built-in IdP federation | Federated identity provider |
| **Groups** | Native groups → JWT via mapper | No native groups (use custom attributes) |
| **Local dev** | ✅ Easy (Docker) | ⚠️ Needs internet + AWS account |
| **Production ops** | You manage upgrades/backups | AWS manages everything |
| **Multi-tenancy** | User attributes → JWT | Custom attributes → Lambda → JWT |
| **Token debug** | `/debug/token` in your app | jwt.io or AWS CLI |
| **Best for** | On-prem, full control, EU data residency | AWS-native teams, no ops overhead |

---

## Troubleshooting

### `tenantId` missing from JWT

| Provider | Fix |
|---|---|
| **Keycloak** | Check User Attribute mapper is on the `control-plane-web-dedicated` scope, not a different scope |
| **Keycloak** | Confirm the user attribute is set: Admin → Users → select user → Attributes tab |
| **Cognito** | Check Pre Token Gen Lambda is attached to the user pool |
| **Cognito** | Confirm Lambda has `adminUpdateUserAttributes` permission on the user pool |
| **Both** | User hasn't completed the `/onboarding` flow yet — this is expected |

### `401 Unauthorized` from backend

```bash
# Keycloak — check realm and client ID match
curl http://localhost:8180/realms/decisionmesh/.well-known/openid-configuration

# Cognito — check issuer URL matches user pool region
curl https://cognito-idp.us-east-1.amazonaws.com/us-east-1_XXXXXXXXX/.well-known/openid-configuration
```

### CORS errors in browser

```properties
# Keycloak — add frontend URL to Web Origins in client settings
# AND in application.properties:
quarkus.http.cors.origins=http://localhost:5173

# Cognito — ensure Allowed callback URLs includes your frontend URL
```

### Google login creates session but no user in Keycloak Users list

This is expected — federated (Google) users are brokered through Keycloak.
They appear in **Sessions**, not always in **Users** unless the first-login flow creates a local user.

```
Keycloak → Identity Providers → Google
  → First login flow: first broker login   ← creates local user on first Google login
  → Sync mode: FORCE                       ← syncs email/name on every login
```

### User exists in Keycloak but `provisionBasicUser` can't find them in DB

The backend uses `externalId = keycloak sub (UUID)`.
Confirm the JWT `sub` claim matches what's stored in `users.external_user_id`:

```sql
SELECT external_user_id, email, tenant_id FROM users WHERE email = 'user@example.com';
```

---

> 💡 **New developer checklist:**
> - [ ] Keycloak running on `:8180` or Cognito User Pool created
> - [ ] Realm / User Pool configured with correct client
> - [ ] Three token mappers added (`tenantId`, `userId`, `accountType`)
> - [ ] Admin service account configured (Keycloak) or Lambda trigger deployed (Cognito)
> - [ ] `application.properties` updated with correct URLs and secrets
> - [ ] Frontend `.env.development` updated
> - [ ] Login → `/debug/token` → confirm JWT claims ✅