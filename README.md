# Open Source Research Conference CMS

**A comprehensive, reusable, and open-source Conference Management System (CMS) designed for Universities and Research Institutes.**

Created by **[@lalithk90](https://github.com/lalithk90)**.

---

## 📖 Overview

This project is a production-grade, monolithic Spring Boot application built to manage the entire lifecycle of a research conference. It is designed to be **downloaded, tested, and hosted locally** by universities or institutes.

The system supports **multiple conferences** by allowing administrators to create and switch between "Active Conference Profiles." This means a single installation can manage different conferences year over year without changing the core code.

## ✨ Key Features

### 1. 🏢 Conference Management
*   **Dynamic Configuration**: Create and manage conference details (Title, Venue, Dates, Logo).
*   **Multi-Conference Support**: Host multiple events over time; switch the "Active" conference instantly.
*   **Public Portal**: Auto-generated landing pages for Home, About, Speakers, and Schedule.

### 2. 📝 Submission System
*   **Paper Submission**: Authors can submit abstracts and full papers (PDF).
*   **Versioning**: Support for submitting revised versions (v1, v2, etc.).
*   **Track Management**: Organize submissions by tracks or sub-themes.

### 3. ⚖️ Advanced Review Process
*   **Reviewer Bidding**: Reviewers can bid on papers they want to review (Eager, Willing, Conflict).
*   **Smart Assignment**: Automated algorithm assigns papers based on bids and load balancing.
*   **Conflict of Interest**: Built-in detection and manual declaration of COIs.
*   **Scoring & Decisions**: Admins make final acceptance/rejection decisions based on reviewer scores.

### 4. 📅 Scheduling & Program
*   **Session Management**: Create rooms, time slots, and sessions.
*   **Program Builder**: Assign accepted papers to specific sessions.
*   **Public Schedule**: Automatically generates a viewable schedule for attendees.

### 5. 🎟️ Registration & Payments
*   **Flexible Payment Providers**:
    *   **Stripe**: Integrated credit card processing.
    *   **PayPal**: Secure checkout.
    *   **Local Bank Transfer**: Display custom bank details for manual verification.
    *   **Free**: Support for free events.
*   **Ticket Types**: Manage different rates for Students, Regular attendees, etc.

## 🚀 Getting Started

### Prerequisites
*   **Java 17** or higher
*   **MySQL 8.0**
*   **Git**

### Installation

1.  **Clone the Repository**
    ```bash
    git clone https://github.com/lalithk90/research-conference-cms.git
    cd research-conference-cms
    ```

2.  **Configure Database**
    *   `src/main/resources/application.properties` is gitignored (it holds real local
        credentials once filled in) and not present in a fresh checkout. Copy the template
        and fill in your own values:
        ```bash
        cp src/main/resources/application.properties.example src/main/resources/application.properties
        ```
    *   At minimum, set your local MySQL credentials and a generated encryption key:
        ```properties
        spring.datasource.username=${DB_USER:your_mysql_user}
        spring.datasource.password=${DB_PASS:your_mysql_password}
        app.secrets.encryption-key=${PAYMENT_SECRETS_KEY:a-generated-key}
        ```
        Generate the encryption key with `openssl rand -base64 32`. The database itself
        (`brain_boost` by default, per the JDBC URL) is created automatically on first boot.
    *   There is no in-memory/H2 dev profile -- this is the only configuration profile, and it
        always runs against a real local (or production) MySQL instance.

3.  **Run the Application**
    *   **Mac/Linux**:
        ```bash
        ./gradlew bootRun
        ```
    *   **Windows**:
        ```bash
        gradlew.bat bootRun
        ```

4.  **Access the System**
    *   Open your browser and go to: `http://localhost:8080`
    *   **First-run admin account**: on first boot (when no admin account exists yet), the
        application generates a random password for the admin email — `asakahatapitiya@gmail.com`
        by default, or whatever you set `app.bootstrap.admin-email` to — and prints it once to
        the server log:
        ```
        =====================================================
         GENERATED ADMIN ACCOUNT (save this now, shown once)
         Email:    asakahatapitiya@gmail.com
         Password: <random>
        =====================================================
        ```
        Copy the password from the log and log in immediately — it is never stored anywhere
        else in plaintext. Change it right away via Account Settings -> Set/change password.

## 🛠️ Usage Workflow

1.  **Initial Setup**: Log in as Admin.
2.  **Create Conference**: Go to the Dashboard -> Create Conference.
3.  **Configure Payments**: Select your preferred payment method (e.g., Local Bank for internal testing).
4.  **Activate**: Set the conference as "Active". The public homepage will now reflect this event.
5.  **Invite Users**: Open registration or invite reviewers/authors.

## 🔒 Security & Deployment

This application handles personal data (names, emails, ORCID IDs, paper submissions) and
should be deployed with the following in place:

*   **TLS/HTTPS**: the application does not terminate TLS or redirect HTTP to HTTPS itself.
    Run it behind a reverse proxy or load balancer (nginx, Caddy, a cloud load balancer) that
    terminates TLS, in any deployment reachable over a public or untrusted network.
*   **Database encryption at rest**: the application does not encrypt any column itself.
    Enable encryption at rest at the database/infrastructure layer (cloud-managed database
    encryption, or disk-level encryption for a self-hosted MySQL instance) for any deployment
    handling real personal data.
*   **First-run admin password**: retrieve it from the server log immediately after first boot
    (see above) and change it via Account Settings. It is generated fresh per deployment and
    never checked into source control or configuration files.
*   **Access log retention**: this application records every successful login (with IP address
    and, if resolvable, an approximate city/country) and every reviewer paper download in an
    `access_logs` table for audit purposes. Both the IP address and resolved location are
    personal data under PDPA -- self-hosting institutions should periodically purge old rows
    per their own retention policy (e.g. 90 days). The application does not do this
    automatically.
*   **GeoLite2 database staleness**: the bundled `GeoLite2-City.mmdb` (used for the login
    location feature above) will go stale over time. MaxMind's distribution terms require a
    (free) MaxMind account to download updates -- self-hosting institutions should periodically
    replace `src/main/resources/geoip/GeoLite2-City.mmdb` with a current copy.
*   **Login IP resolution behind a reverse proxy**: the access log records `request.getRemoteAddr()`
    as the login's IP address. Behind the reverse proxy this README already requires for TLS
    termination, that call returns the proxy's own address, not the real client IP -- every
    logged-in row would show the proxy's IP and no resolvable location. To record the real
    client IP, configure the reverse proxy to set `X-Forwarded-For` (and strip any
    client-supplied value of that header first, so it can't be spoofed), and set
    `server.forward-headers-strategy=framework` in this application's configuration so Spring
    resolves `getRemoteAddr()` from that header.

## 📜 Citation & Attribution

This project is open source and free to use for educational and research purposes.

**If you use this software for your conference, research, or as a base for your own project, please cite the original creator:**

> **Original Author**: Lalith K ([@lalithk90](https://github.com/lalithk90))
> **Project**: Open Source Research Conference CMS

A link back to the original repository is appreciated.

## 🤝 Contributing

Contributions are welcome! Please fork the repository and submit a Pull Request.

1.  Fork the Project
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4.  Push to the Branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
