package io.gomob.feature.profile

/**
 * 「我的」tab 二级路由表 — GomobNavHost 注册的唯一真理源。
 *
 * root 路由 "profile" 属顶层 tab 基建, 由 GomobNavHost 的 TABS 定义;
 * 这里只维护 profile 子图: 设置(原右滑抽屉改独立子页) / 我的资料 / 我的案例 /
 * 账号与安全 / 通知 / 关于 / 主题 / 历史日历。
 */
const val PROFILE_SETTINGS_ROUTE = "profile/settings"
const val PROFILE_PERSONAL_ROUTE = "profile/personal"
const val PROFILE_CASES_ROUTE = "profile/cases"
const val PROFILE_ACCOUNT_ROUTE = "profile/account"
const val PROFILE_NOTIFICATION_ROUTE = "profile/notification"
const val PROFILE_ABOUT_ROUTE = "profile/about"
const val PROFILE_THEME_ROUTE = "profile/theme"
const val PROFILE_HISTORY_ROUTE = "profile/history"
