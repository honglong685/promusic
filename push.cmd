@echo off
chcp 65001 >nul
cd /d E:\ANDRIOD\promusic
echo ============================================
echo  ProMusic - 推送更新到 GitHub
echo ============================================
echo.

git push -u origin main
if %errorlevel% neq 0 (
    echo.
    echo [失败] 推送未成功。常见原因与处理：
    echo.
    echo  1) 如果 GitHub 上还没有 promusic 仓库：
    echo     请先打开 https://github.com/new
    echo     Owner 选 honglong685，Repository name 填 promusic，选 Public
    echo     创建后再双击本脚本重试。
    echo.
    echo  2) 如果是登录/授权失败：
    echo     用户名填 honglong685，密码栏填 GitHub Personal Access Token
    echo     (Token 在 github.com -> Settings -> Developer settings 生成，
    echo      需勾选 repo 权限)
    echo.
    echo  3) 如果网络连不上 GitHub：
    echo     请确认本机网络可访问 github.com，或配置代理/VPN 后重试。
    echo.
    pause
    exit /b 1
)

echo.
echo [成功] 已推送至 https://github.com/honglong685/promusic
echo.
pause
