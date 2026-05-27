# P2P Chat - Ứng dụng Chat Ngang Hàng

Ứng dụng chat ngang hàng (P2P) viết bằng Java với giao diện Swing, cho phép các peers kết nối TCP trực tiếp để chat 1-1, nhóm và broadcast.

**Kiến trúc:**

- **Bootstrap Server** (cổng 9000): Đăng ký và quản lý peers
- **Relay Server** (cổng 9100): Lưu tin nhắn offline
- **Peer Client**: Ứng dụng chat với giao diện Swing

## ⚙️ Yêu cầu hệ thống

- **JDK**: 21+
- **MySQL**: 8.0+
- **Maven**: 3.9+
- **OS**: Windows / Linux / macOS

---

## 🚀 Cài đặt nhanh

### 1. Clone Project

```bash
git clone <repo-url>
cd p2p-chat
```

### 2. ⚠️ Cấu hình thông tin Database MySQL (QUAN TRỌNG!)

Trước khi build, **bạn phải thay đổi thông tin DB** ở 3 file sau:

#### File 1: RelayDatabaseConfig

**File:** `relay-server/src/main/java/com/p2pchat/relay/RelayDatabaseConfig.java`

Tìm dòng:

```java
private String username;
private String password;
```

Thay thành username và password MySQL của bạn:

```java
private String username = "your_username";
private String password = "your_password";
```

#### File 2: DatabaseConfig (Peer Client)

**File:** `peer-client/src/main/java/com/p2pchat/peer/repository/DatabaseConfig.java`

Tìm dòng:

```java
private String username;
private String password;
```

Thay thành username và password MySQL của bạn:

```java
private String username = "your_username";
private String password = "your_password";
```

#### File 3: PeerApp

**File:** `peer-client/src/main/java/com/p2pchat/peer/PeerApp.java`

Tìm dòng:

```java
private static final String DB_USER;
private static final String DB_PASSWORD;
```

Thay thành username và password MySQL của bạn:

```java
private static final String DB_USER = "your-username";
private static final String DB_PASSWORD = "your-password";
```

### 3. Config IP máy vào RelayDefaults (QUAN TRỌNG) và BootstrapDefaults trong peer-client

**File:** `peer-client/src/main/java/com/p2pchat/peer/RelayDefaults.java`
**File:** `peer-client/src/main/java/com/p2pchat/peer/BootstrapDefaults.java`

Chạy ipconfig trên cmd trên máy để lấy IP:

```
Ethernet adapter vEthernet (Default Switch): // ví dụ

Connection-specific DNS Suffix . :
Link-local IPv6 Address . . . . . : fe80::4de8:570a:660b:88fe%14
IPv4 Address. . . . . . . . . . . : 172.23.160.1                // IP sẽ lấy
Subnet Mask . . . . . . . . . . . : 255.255.240.0
Default Gateway . . . . . . . . . :
```

**Tìm dòng trong RelayDefaults và BootstrapDefaults:**

```java
public static final String HOST;
```

Thay thành IP của bạn:

```java
public static final String HOST = "172.23.160.1"; // ip chỉ mang tính chất tham khảo
```

### 4. Build Project

```bash
# Build toàn bộ project (skip tests)
mvn clean package -DskipTests
```

Sau khi build thành công, bạn sẽ có 3 JAR files:

- `bootstrap-server/target/bootstrap-server.jar`
- `relay-server/target/relay-server.jar`
- `peer-client/target/peer-client.jar`

### 4. Setup MySQL Database

Đảm bảo MySQL đang chạy trên cổng 3306 với user `root` và password của bạn.

---

## 🎯 Hướng dẫn chạy project

### Bước 1: Khởi động Bootstrap Server

Mở terminal và chạy:

```bash
java -jar bootstrap-server/target/bootstrap-server.jar 9000 localhost 3306 root <your_password>
```

**Tham số:**

- `9000`: Cổng server
- `localhost`: Host MySQL
- `3306`: Port MySQL
- `root`: Username MySQL
- `<your_password>`: Password MySQL của bạn

**Sau khi chạy thành công**, terminal sẽ hiển thị:

```
Bootstrap Server started on port 9000
Listening on: x.x.x.x:9000
```

**Lưu ý IP LAN này**, bạn sẽ cần dùng nó cho peer kết nối.

---

### Bước 2: Khởi động Relay Server (Tùy chọn)

Mở **terminal thứ 2** và chạy:

```bash
java -jar relay-server/target/relay-server.jar 9100 localhost 3306 root <your_password>
```

**Tham số:**

- `9100`: Cổng Relay Server
- `localhost`: Host MySQL
- `3306`: Port MySQL
- `root`: Username MySQL
- `<your_password>`: Password MySQL của bạn

Relay Server lưu tin nhắn offline. Bạn có thể bỏ qua bước này nếu không cần tính năng này.

---

### Bước 3: Khởi động Peer Clients

Mở **terminal thứ 3** (và các terminal tiếp theo cho các peer khác):

```bash
java -jar peer-client/target/peer-client.jar
```

Giao diện đăng nhập sẽ xuất hiện:

```
Username: [nhập tên của bạn]
My Port: [9100]  (cổng này để peer khác kết nối tới)
[Login]
```

Nhập username và chọn một cổng (VD: 9100, 9101, 9102, ...)

---

### Bước 4: Kết nối tới mạng

Sau khi đăng nhập, chọn **"Connect via Bootstrap"** hoặc **"Connect via Known Peer"**:

**Cách 1: Qua Bootstrap Server**

- Nhập IP Bootstrap: `x.x.x.x` (IP từ log ở Bước 1)
- Nhập cổng: `9000`
- Click **Connect**

**Cách 2: Qua Known Peer**

- Nhập IP + Port của một peer đã biết
- Click **Connect**

---

### Thay đổi cổng mặc định

Nếu muốn thay đổi cổng mặc định (IP), chỉnh sửa các file:

| File                                                                               | Cấu hình                     |
| ---------------------------------------------------------------------------------- | ---------------------------- |
| [bootstrap-server/src/main/java/com/p2pchat/bootstrap/BootstrapNetworkConfig.java] | Cấu hình Bootstrap Server    |
| [peer-client/src/main/java/com/p2pchat/peer/BootstrapDefaults.java]                | IP + Port mặc định Bootstrap |
| [peer-client/src/main/java/com/p2pchat/peer/RelayDefaults.java]                    | IP + Port mặc định Relay     |

## ⚙️ Cấu hình chi tiết

Sau khi chỉnh sửa, build lại:

```bash
mvn clean package -DskipTests
```

### Firewall & Mạng LAN

| Cổng    | Mục đích           |
| ------- | ------------------ |
| `3306`  | MySQL Database     |
| `9000`  | Bootstrap Server   |
| `9100`  | Relay Server       |
| `9100+` | Peer Clients (P2P) |

Trên **Windows**, bạn có thể cần mở các cổng này trên Windows Defender Firewall.

---

## 💬 Hướng dẫn sử dụng

### Chat 1-1

1. Click vào một peer trong danh sách **Peers**
2. Nhập tin nhắn ở ô input phía dưới
3. Nhấn **Enter** hoặc click **Send**

### Tạo nhóm

1. Chuyển sang tab **Groups**
2. Click **+ New Group**
3. Nhập tên nhóm
4. Thêm thành viên bằng nút **+** trong nhóm

### Gửi file

1. Mở cửa sổ chat (1-1 hoặc nhóm)
2. Click biểu tượng **📎 (Attach File)**
3. Chọn file (tối đa 50MB)
4. File sẽ được gửi qua P2P

### Broadcast

1. Click nút **Broadcast** trong cửa sổ chat
2. Nhập tin nhắn
3. Tin nhắn sẽ gửi tới **tất cả** peers đang online

---

## 🔍 Troubleshooting

| Lỗi                           | Nguyên nhân                | Giải pháp                              |
| ----------------------------- | -------------------------- | -------------------------------------- |
| `Connection refused`          | MySQL chưa chạy            | Khởi động MySQL trước                  |
| `Communications link failure` | MySQL host/port sai        | Kiểm tra thông số MySQL                |
| Peer không nhìn thấy nhau     | Sai IP Bootstrap           | Dùng IPv4 LAN thay vì `localhost`      |
| Không kết nối được            | Firewall chặn cổng         | Mở các cổng trên firewall              |
| Không kết nối được tới Relay  | Sai IP trong RelayDefaults | Thay Ip hiện tại bằng Ip máy/bootstrap |
| Tin nhắn offline không nhận   | Relay Server chưa chạy     | Khởi động Relay Server                 |
| File transfer không hoạt động | Peer offline               | Đảm bảo peer đích online khi gửi file  |

### Kiểm tra nhanh

```bash
# Kiểm tra MySQL
mysql -u root -p -e "SHOW DATABASES;"

# Kiểm tra port đang mở (Windows)
netstat -an | findstr LISTENING

# Kiểm tra Bootstrap Server (từ terminal khác)
telnet 192.168.x.x 9000
```

---

## 📝 Cấu trúc Project

```
p2p-chat/
├── bootstrap-server/          # Server để đăng ký peers
│   └── src/main/java/com/p2pchat/bootstrap/
├── relay-server/              # Server để lưu tin nhắn offline
│   └── src/main/java/com/p2pchat/relay/
├── peer-client/               # Ứng dụng chat client
│   └── src/main/java/com/p2pchat/peer/
│       ├── gui/               # Giao diện Swing
│       ├── network/           # Xử lý kết nối mạng
│       ├── model/             # Dữ liệu (Message, Group...)
│       ├── repository/        # Truy cập database
│       └── service/           # Mã hóa, xử lý logic
└── pom.xml                    # Maven config
```

---

## 🔒 Bảo mật

- **Mã hóa**: Sử dụng AES-128 cho tin nhắn
- **Key**: Pre-shared key (phù hợp cho demo)
- **Production**: Nên dùng TLS/SSL + Diffie-Hellman key exchange

---
