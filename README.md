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
    *   Create a MySQL database named `conference_cms`.
    *   Update `src/main/resources/application.properties` (or `application-dev.properties`) with your credentials:
        ```properties
        spring.datasource.url=jdbc:mysql://localhost:3306/conference_cms
        spring.datasource.username=root
        spring.datasource.password=your_password
        ```

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
    *   **Default Admin Credentials** (seeded on first run):
        *   Email: `admin@icosiam.com`
        *   Password: `admin`

## 🛠️ Usage Workflow

1.  **Initial Setup**: Log in as Admin.
2.  **Create Conference**: Go to the Dashboard -> Create Conference.
3.  **Configure Payments**: Select your preferred payment method (e.g., Local Bank for internal testing).
4.  **Activate**: Set the conference as "Active". The public homepage will now reflect this event.
5.  **Invite Users**: Open registration or invite reviewers/authors.

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
