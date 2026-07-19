import type { Metadata } from "next";
import { headers } from "next/headers";
import "./globals.css";

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const host = requestHeaders.get("host");
  const protocol = requestHeaders.get("x-forwarded-proto") || "https";
  const socialImage = host ? `${protocol}://${host}/og.png` : undefined;

  return {
    title: "峡谷 BP · 王者荣耀实时选将助手",
    description: "查询英雄克制关系，并结合实时对位数据与英雄梯度推荐补位英雄。",
    openGraph: {
      title: "峡谷 BP · 实时选将助手",
      description: "克制关系、英雄梯度与阵容补位，一屏完成。",
      type: "website",
      locale: "zh_CN",
      images: socialImage ? [{ url: socialImage, width: 1729, height: 910 }] : undefined,
    },
    twitter: {
      card: "summary_large_image",
      title: "峡谷 BP · 实时选将助手",
      description: "克制关系、英雄梯度与阵容补位，一屏完成。",
      images: socialImage ? [socialImage] : undefined,
    },
  };
}

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
