package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.mindrot.jbcrypt.BCrypt;

import com.google.gson.JsonObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

@WebServlet("/login")
public class LoginServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String usuario = request.getParameter("usuario");
        String clave = request.getParameter("clave");
        JsonObject jsonResponse = new JsonObject();

        try (Connection conn = DatabaseUtil.getConnection()) {
            String sql = "SELECT Email, Clave, administrador, verificado, fecha_baja FROM voluntarios WHERE Usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, usuario);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String storedHash = rs.getString("Clave");
                        String verificado = rs.getString("verificado");
                        java.sql.Date fechaBaja = rs.getDate("fecha_baja");

                        // 1. Comprobar si la cuenta está dada de baja
                        if (fechaBaja != null) {
                            jsonResponse.addProperty("success", false);
                            jsonResponse.addProperty("message", "Esta cuenta ha sido dada de baja.");
                            LogUtil.logOperation("LOGIN-FAIL", usuario, "Intento de login en cuenta inactiva.");
                        }
                        // 2. Comprobar la contraseña
                        else if (storedHash != null && BCrypt.checkpw(clave, storedHash)) {
                            // 3. Comprobar si la cuenta está verificada
                            if ("S".equals(verificado)) {
                                String esAdmin = rs.getString("administrador");
                                String email = rs.getString("Email");
                                
                                // Crear sesión
                                HttpSession session = request.getSession(true);
                                session.setAttribute("usuario", usuario);
                                session.setAttribute("email", email); // Guardamos el email en la sesión
                                session.setAttribute("isAdmin", "S".equals(esAdmin));
                                session.setMaxInactiveInterval(10 * 60); // 10 minutos de inactividad

                                jsonResponse.addProperty("success", true);
                                jsonResponse.addProperty("isAdmin", "S".equals(esAdmin));
                                
                                LogUtil.logOperation("LOGIN-OK", usuario, "Inicio de sesión exitoso.");

                            } else {
                                jsonResponse.addProperty("success", false);
                                jsonResponse.addProperty("message", "Tu cuenta aún no ha sido verificada. Por favor, revisa tu correo electrónico.");
                                LogUtil.logOperation("LOGIN-FAIL", usuario, "Intento de login sin verificar email.");
                            }
                        } else {
                            jsonResponse.addProperty("success", false);
                            jsonResponse.addProperty("message", "Usuario o contraseña incorrectos.");
                            LogUtil.logOperation("LOGIN-FAIL", usuario, "Contraseña incorrecta.");
                        }
                    } else {
                        jsonResponse.addProperty("success", false);
                        jsonResponse.addProperty("message", "Usuario o contraseña incorrectos.");
                         LogUtil.logOperation("LOGIN-FAIL", usuario, "Usuario no encontrado.");
                    }
                }
            }
        } catch (SQLException e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error de base de datos. Inténtalo más tarde.");
            e.printStackTrace();
        }

        response.getWriter().write(jsonResponse.toString());
    }
}
