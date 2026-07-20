# TÀI LIỆU THIẾT KẾ HỆ THỐNG - MC PARTY (Mario Party trong Minecraft)

**Phiên bản:** 1.0  
**Ngày cập nhật:** 14/07/2026  
**Mục tiêu ban đầu:** Hỗ trợ 400+ người chơi đồng thời  
**Phong cách:** Kết hợp giữa Mario Party Superstars + Cytooxien Minecraft Party + Mr_Kheese map

---

## 1. Mục tiêu dự án

- Xây dựng một hệ thống **Mario Party** quy mô lớn trong Minecraft.
- Giữ được cảm giác vui vẻ, tương tác cao như bản gốc (ưu tiên instance nhỏ: **4–8 người**, tối đa 10–12 người).
- Hỗ trợ **10–12 minigame** ở giai đoạn đầu.
- Dễ mở rộng sau này (thêm minigame, board, rotation như Cytooxien).
- Sử dụng **AdvancedSlimePaper** để quản lý nhiều world/instance hiệu quả.

---

## 2. Tech Stack

| Thành phần              | Công nghệ                          | Ghi chú |
|-------------------------|------------------------------------|--------|
| Server Software         | **AdvancedSlimePaper**             | Load/unload world nhanh, tiết kiệm RAM |
| Proxy                   | **Velocity**                       | Tốt hơn BungeeCord |
| Plugin chính            | Java (Bukkit/Paper API)            | Tự viết |
| Database (Lâu dài)      | **MySQL**                          | Lưu stats, coin, star tổng |
| Cache / Runtime         | **Redis**                          | Trạng thái instance, queue, sync multi-server |
| World Management        | AdvancedSlimePaper (Slime format)  | Dynamic load/unload |
| Matchmaking             | Tự viết (Room-based + Dynamic countdown) | 60s (>10p), 30s (>20p), 10s (40p) |

---

## 3. Kiến trúc Tổng thể (cho 400 người)

```
Velocity Proxy
     │
     ├── Lobby Server (1 Paper server)
     │
     ├── Game Server 1 (64GB) → 12–18 instance MC Party
     ├── Game Server 2 (64GB) → 12–18 instance MC Party
     └── Game Server 3 (64GB) → 12–18 instance MC Party   ← (khi scale)
```

**Phân công:**
- **Lobby Server**: Sảnh chờ, tạo phòng, matchmaking, GUI.
- **Game Server**: Chạy các `PartyInstance` thực tế (board + minigame).
- Mỗi **Game Server** chạy nhiều instance cùng lúc nhờ AdvancedSlimePaper.

---

## 4. Thiết kế Plugin (Core Classes)

### Các class chính:

| Class                  | Vai trò | Ghi chú |
|------------------------|--------|--------|
| `PartyManager`         | Quản lý tất cả instance, matchmaking, tạo/hủy instance | Trung tâm |
| `PartyInstance`        | Đại diện cho 1 ván chơi (1 board) | Chứa players, state, coins, stars, board logic |
| `Board`                | Logic bảng chơi (di chuyển, ô, item, shop) | Lấy cảm hứng Cytooxien (coin shop) |
| `MinigameManager`      | Quản lý và chọn minigame | Hỗ trợ random + specific |
| `Minigame` (Interface) | Interface cho tất cả minigame | Dễ mở rộng |
| `DatabaseManager`      | Kết nối MySQL | Lưu stats lâu dài |
| `RedisManager`         | Kết nối Redis | Lưu trạng thái runtime nhanh |

### Trạng thái của `PartyInstance`:
`WAITING` → `STARTING` → `PLAYING` → `ENDING` → `CLEANUP`

---

## 5. Luồng Hoạt động Chính

### 5.1. Tạo & Join Instance
1. Người chơi ở Lobby → bấm tạo phòng hoặc join.
2. `PartyManager` kiểm tra Redis → tìm instance đang chờ phù hợp.
3. Nếu không có → tạo `PartyInstance` mới → load world bằng AdvancedSlimePaper.
4. Người chơi được đưa vào instance.

### 5.2. Trong ván chơi (Board)
- Người chơi lần lượt ném xúc xắc → di chuyển trên board.
- Sau khi tất cả đã di chuyển → `MinigameManager` chọn minigame → chạy.
- Minigame kết thúc → trả coin/star về instance.
- Lặp lại cho đến khi hết vòng hoặc ai đó về đích.

### 5.3. Kết thúc ván
- Tính điểm cuối cùng.
- Lưu kết quả vào MySQL.
- Hủy instance → unload world.

---

## 6. Minigame System

**Giai đoạn đầu:** 10–12 minigame

**Loại minigame nên có:**
- 4–5 Free-for-all
- 2–3 2v2
- 1–2 1v3
- 1–2 Duel (1v1)

**Thiết kế:**
- Dùng **Interface `Minigame`**
- `MinigameManager` chịu trách nhiệm chọn và chạy minigame
- Mỗi minigame có thể nhận danh sách người chơi + `PartyInstance` để trả kết quả

**Mục tiêu sau này:** Có thể làm rotation minigame như Cytooxien.

---

## 7. Database & Storage Strategy

### MySQL (Persistent)
- Bảng `players`
- Bảng `player_stats`
- Bảng `minigame_history`

### Redis (Runtime - Rất quan trọng)
- `party:instance:{id}` → trạng thái instance
- `party:player:{uuid}:current` → instance hiện tại của người chơi
- `party:queue` → danh sách chờ

**Lý do dùng Redis:**
- Nhanh khi có nhiều instance.
- Dễ sync khi sau này scale sang nhiều Game Server.

---

## 8. World & Instance Management

- Mỗi `PartyInstance` = 1 world riêng (dùng AdvancedSlimePaper).
- **Chỉ load world** khi instance được tạo.
- **Unload ngay** khi instance kết thúc.
- Giới hạn số instance active trên 1 Game Server (tùy RAM).

**Khuyến nghị ban đầu (64GB RAM):**
- Tối đa **15–18 instance** chạy cùng lúc trên 1 Game Server.

---

## 9. Matchmaking (Dynamic Countdown)

- >10 người → đếm ngược **60 giây**
- >20 người → đếm ngược **30 giây**
- Đủ 40 người (hoặc max) → đếm ngược **10 giây** rồi vào ngay

**Mục tiêu:** Giảm thời gian chờ nhưng vẫn giữ instance nhỏ (4–10 người).

---

## 10. Scaling Roadmap

| Giai đoạn     | Số người     | Số Game Server | Instance / Server | Ghi chú |
|---------------|--------------|----------------|-------------------|--------|
| Phase 1       | 100–150      | 1              | 10–12             | Test & ổn định |
| Phase 2       | 300–400      | 2–3            | 12–18             | Mục tiêu ban đầu |
| Phase 3       | 500+         | 4+             | 15–20             | Scale ngang + Redis sync |

---

## 11. Các Quyết định Quan trọng

- **Ưu tiên instance nhỏ** (4–8 người) để giữ cảm giác Mario Party.
- Dùng **AdvancedSlimePaper** để quản lý nhiều world.
- Kết hợp **Board + Coin Shop** (lấy cảm hứng Cytooxien) thay vì chỉ thu thập sao.
- Bắt đầu với **10–12 minigame chất lượng** thay vì làm nhiều.
- Dùng **Redis** cho trạng thái runtime ngay từ đầu.
- Kiến trúc **Lobby + nhiều Game Server** khi scale.

---

## 12. Roadmap Phát triển (Gợi ý)

**Phase 1 (Cơ bản)**
- `PartyInstance` + `PartyManager`
- 1 board đơn giản
- 4–5 minigame
- Redis cho trạng thái

**Phase 2**
- Đầy đủ 10–12 minigame
- Coin + Shop trên board
- GUI tạo/join phòng

**Phase 3**
- Tách Lobby + Game Server
- Velocity Proxy
- Hỗ trợ nhiều Game Server

**Phase 4 (Nâng cao)**
- Rotation minigame & map (như Cytooxien)
- Thêm nhiều board
- Hệ thống party, rank, stats chi tiết

---

**Tài liệu này đã đủ chi tiết** để bạn copy và đưa cho AI khác (hoặc dùng lại với mình) mà không cần giải thích lại từ đầu.

Bạn muốn mình bổ sung thêm phần nào không? Ví dụ:
- Chi tiết code mẫu cho `PartyInstance` và `MinigameManager`
- Sơ đồ luồng chi tiết hơn
- Cách thiết kế bảng (Board) và Coin Shop

Cứ nói, mình sẽ cập nhật tài liệu này ngay.
