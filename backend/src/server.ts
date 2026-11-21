import express from "express";
import cors from "cors";
import helmet from "helmet";
import rateLimit from "express-rate-limit";
import dotenv from "dotenv";
import { initFirebaseAdmin } from "./middleware/auth";
import { lockerRouter } from "./routes/lockerRoutes";

dotenv.config();
initFirebaseAdmin();

const app = express();
const port = Number(process.env.PORT || 8080);

app.use(helmet());
app.use(cors({ origin: true, credentials: true }));
app.use(express.json({ limit: "2mb" }));

const apiLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 300,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many requests. Please slow down." },
});

const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 25,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many authentication attempts." },
});

app.get("/health", (_req, res) => {
  res.json({ service: "DSK LOCKER SQL Backend", status: "ok" });
});

app.use("/api/auth", authLimiter);
app.use("/api", apiLimiter, lockerRouter);

app.listen(port, () => {
  console.log(`DSK LOCKER Secure SQL Backend listening on port ${port}`);
});
