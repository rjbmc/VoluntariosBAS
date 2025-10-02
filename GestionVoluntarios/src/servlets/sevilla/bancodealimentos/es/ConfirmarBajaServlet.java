package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;

import com.google.gson.JsonObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

@WebServlet("/confirmar-baja")
public class ConfirmarBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");
        JsonObject jsonResponse = new JsonObject();
        Connection conn = null;

        if (token == null || token.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Token no proporcionado.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false); // Iniciar transacción

            String usuario = null;
            
            // 1. Buscar al voluntario con el token y verificar que no ha caducado
            String findUserSql = "SELECT Usuario FROM voluntarios WHERE token_baja = ? AND token_baja_expiry > ?";
            try (PreparedStatement psFind = conn.prepareStatement(findUserSql)) {
                psFind.setString(1, token);
                psFind.setTimestamp(2, Timestamp.from(Instant.now()));
                
                try (ResultSet rs = psFind.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                    }
                }
            }

            if (usuario != null) {
                // 2. Si se encuentra el usuario, proceder con la baja lógica
                String updateUserSql = "UPDATE voluntarios SET fecha_baja = ?, token_baja = NULL, token_baja_expiry = NULL WHERE Usuario = ?";
                try (PreparedStatement psUpdate = conn.prepareStatement(updateUserSql)) {
                    psUpdate.setTimestamp(1, Timestamp.from(Instant.now())); 
                    psUpdate.setString(2, usuario);
                    
                    int rowsAffected = psUpdate.executeUpdate();
                    
                    if (rowsAffected > 0) {
                        // 3. Registrar la operación en el log
                        LogUtil.logOperation(conn, "BAJA-CONFIRM", usuario, "El usuario ha confirmado su baja.");
                        
                        conn.commit(); // Confirmar la transacción
                        
                        jsonResponse.addProperty("success", true);
                        jsonResponse.addProperty("message", "Tu cuenta ha sido dada de baja correctamente.");
                        response.getWriter().write(jsonResponse.toString());
                    } else {
                        throw new SQLException("No se pudo actualizar el registro del voluntario.");
                    }
                }
            } else {
                // 4. Si no se encuentra el usuario (token inválido o caducado)
                conn.rollback(); // Revertir por si acaso, aunque no se hizo nada
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                jsonResponse.addProperty("success", false);
                jsonResponse.addProperty("message", "El enlace de confirmación no es válido o ha caducado. Por favor, solicita la baja de nuevo.");
                response.getWriter().write(jsonResponse.toString());
            }

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error de base de datos. Inténtalo más tarde.");
            response.getWriter().write(jsonResponse.toString());
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

