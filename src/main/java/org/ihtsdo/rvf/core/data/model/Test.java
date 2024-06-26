package org.ihtsdo.rvf.core.data.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;

import javax.persistence.*;

@Entity
@Table(name = "test")
public class Test {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String name;
	private String description;
	private TestType type = TestType.UNKNOWN;
	@OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
	@JoinColumn(name = "command_id")
	@JsonManagedReference
	private ExecutionCommand command;

	public Test() {
	}

	public Test(final Long id, final String name) {
		this.id = id;
		this.name = name;
	}

	public Long getId() {
		return id;
	}

	public void setId(final Long id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(final String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(final String description) {
		this.description = description;
	}

	public TestType getType() {
		return type;
	}

	public void setType(final TestType type) {
		this.type = type;
	}

	public ExecutionCommand getCommand() {
		return command;
	}

	public void setCommand(final ExecutionCommand command) {
		this.command = command;
		this.command.setTest(this);
	}

	@Override
	public String toString() {
		return "Test [id=" + id + ", name=" + name + ", description="
				+ description + ", type=" + type + "]";
	}
}
